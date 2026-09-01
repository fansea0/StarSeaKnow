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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
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
@RequiredArgsConstructor
@Slf4j
public class FileServiceImpl extends ServiceImpl<FileMapper, com.starsea.ai.domain.File> implements FileService {
    
    @Value("${file.uploadPath}")
    private String path;

    private final FileMapper fileMapper;
    private final KnowledgeFileMapper knowledgeFileMapper;
    private final KnowledgeMapper knowledgeMapper;
    private final FileProcessingMapper processingMapper;

    @Override
    @Transactional
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
        try {
            Files.createDirectories(uploadRoot);
            try (InputStream input = file.getInputStream()) {
                Files.copy(input, createdPath);
            }
        } catch (FileAlreadyExistsException exception) {
            throw new IllegalStateException("文件已存在!", exception);
        } catch (IOException exception) {
            deleteCreatedPath(createdPath, exception);
            throw new IllegalStateException("文件保存失败!", exception);
        }
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
