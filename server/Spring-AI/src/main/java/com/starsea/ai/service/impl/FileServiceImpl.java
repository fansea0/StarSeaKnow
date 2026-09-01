package com.starsea.ai.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.starsea.ai.auth.AuthContext;
import com.starsea.ai.chunking.model.PipelineState;
import com.starsea.ai.domain.FileProcessing;
import com.starsea.ai.domain.Knowledge;
import com.starsea.ai.domain.KnowledgeFile;
import com.starsea.ai.domain.vo.FileVo;
import com.starsea.ai.mapper.FileMapper;
import com.starsea.ai.mapper.FileProcessingMapper;
import com.starsea.ai.mapper.KnowledgeFileMapper;
import com.starsea.ai.mapper.KnowledgeMapper;
import com.starsea.ai.service.FileService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.List;
import java.util.Set;
import static com.starsea.ai.util.FileUtil.getFileTypeByExtension;

/**
 * @Projectname: Spring-AI
 * @Filename: FileServiceImpl
 * @Author: FANSEA
 * @Date:2025/4/26 16:28
 */
@Service
@Slf4j
public class FileServiceImpl extends ServiceImpl<FileMapper, com.starsea.ai.domain.File> implements FileService {
    
    @Value("${file.uploadPath}")
    private String path;

    private final FileMapper fileMapper;
    private final KnowledgeFileMapper knowledgeFileMapper;
    private final KnowledgeMapper knowledgeMapper;
    private final FileProcessingMapper processingMapper;
    private final TransactionTemplate transactionTemplate;

    public FileServiceImpl(FileMapper fileMapper, KnowledgeFileMapper knowledgeFileMapper,
                           KnowledgeMapper knowledgeMapper, FileProcessingMapper processingMapper,
                           PlatformTransactionManager transactionManager) {
        this.fileMapper = fileMapper;
        this.knowledgeFileMapper = knowledgeFileMapper;
        this.knowledgeMapper = knowledgeMapper;
        this.processingMapper = processingMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public Long uploadToKnowledge(MultipartFile file,Long knowledgeId) {
        long tenantId = requireTenantId();
        String fileName = requireSafeFilename(file.getOriginalFilename());
        Path uploadRoot = Path.of(path).toAbsolutePath().normalize();
        Path createdPath = uploadRoot.resolve(fileName).normalize();
        if (!createdPath.getParent().equals(uploadRoot)) {
            throw new IllegalArgumentException("文件名非法!");
        }
        writePhysicalFile(file, uploadRoot, createdPath);

        try {
            Knowledge knowledge = knowledgeMapper.selectById(knowledgeId);
            if (knowledge == null) {
                throw new IllegalArgumentException("知识库不存在!");
            }
            Long fileId = transactionTemplate.execute(status -> persistUpload(
                    file, fileName, createdPath, tenantId, knowledgeId));
            if (fileId == null) {
                throw new IllegalStateException("upload transaction returned no file id");
            }
            return fileId;
        } catch (RuntimeException databaseFailure) {
            deleteCreatedPath(createdPath, databaseFailure);
            throw databaseFailure;
        }
    }

    @Override
    public List<FileVo> listByKnowledgeId(Long knowledgeId) {
        return fileMapper.selectByKnowledgeId(requireTenantId(), knowledgeId);
    }

    @Override
    public List<com.starsea.ai.domain.File> listEnabledByKnowledgeIds(Long tenantId, Set<Long> knowledgeIds) {
        return fileMapper.selectEnabledByKnowledgeIds(tenantId, knowledgeIds);
    }

    private void writePhysicalFile(MultipartFile file, Path uploadRoot, Path createdPath) {
        boolean ownsCreatedPath = false;
        try {
            Files.createDirectories(uploadRoot);
            try (InputStream input = file.getInputStream();
                 OutputStream output = Files.newOutputStream(createdPath,
                         StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                ownsCreatedPath = true;
                input.transferTo(output);
            }
        } catch (FileAlreadyExistsException exception) {
            throw new IllegalStateException("文件已存在!", exception);
        } catch (IOException exception) {
            if (ownsCreatedPath) {
                deleteCreatedPath(createdPath, exception);
            }
            throw new IllegalStateException("文件保存失败!", exception);
        }
    }

    private Long persistUpload(MultipartFile file, String fileName, Path createdPath,
                               long tenantId, long knowledgeId) {
        com.starsea.ai.domain.File documentFile = new com.starsea.ai.domain.File(
                fileName, file.getSize(), getFileTypeByExtension(fileName), createdPath.toString());
        requireSingleInsert(fileMapper.insert(documentFile), "file");
        if (documentFile.getId() == null) {
            throw new IllegalStateException("file insert did not return an id");
        }
        requireSingleInsert(knowledgeFileMapper.insert(
                new KnowledgeFile(knowledgeId, documentFile.getId())), "knowledge_file");
        FileProcessing processing = new FileProcessing();
        processing.setFileId(documentFile.getId());
        processing.setTenantId(tenantId);
        processing.setKnowledgeId(knowledgeId);
        processing.setPipelineState(PipelineState.UPLOADED.code());
        processing.setProgress(0);
        processing.setPolicySnapshot(Map.of());
        processing.setContextPolicy(Map.of());
        processing.setLockVersion(0);
        requireSingleInsert(processingMapper.insert(processing), "file_processing");
        return documentFile.getId();
    }

    private void deleteCreatedPath(Path createdPath, Throwable failure) {
        try {
            Files.deleteIfExists(createdPath);
        } catch (IOException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
            log.warn("Failed to remove newly created upload {}", createdPath, cleanupFailure);
        }
    }

    private long requireTenantId() {
        AuthContext context = AuthContext.current();
        if (context == null || context.getTenantId() == null) {
            throw new IllegalStateException("当前请求缺少租户上下文");
        }
        return context.getTenantId();
    }

    private String requireSafeFilename(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException("文件名为空!");
        }
        Path name = Path.of(fileName);
        if (name.getNameCount() != 1 || !name.getFileName().toString().equals(fileName)) {
            throw new IllegalArgumentException("文件名非法!");
        }
        return fileName;
    }

    private void requireSingleInsert(int inserted, String table) {
        if (inserted != 1) {
            throw new IllegalStateException(table + " insert did not create exactly one row");
        }
    }
}
