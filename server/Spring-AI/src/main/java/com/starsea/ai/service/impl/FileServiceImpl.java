package com.starsea.ai.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.starsea.ai.auth.AuthContext;
import com.starsea.ai.chunking.model.PipelineState;
import com.starsea.ai.chunking.extraction.ManagedExtractionCache;
import com.starsea.ai.chunking.extraction.DocumentTextExtractorRegistry;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.UUID;
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
    private final ManagedExtractionCache extractionCache;
    private final DocumentTextExtractorRegistry extractorRegistry;
    private final TransactionTemplate transactionTemplate;

    @Autowired
    public FileServiceImpl(FileMapper fileMapper, KnowledgeFileMapper knowledgeFileMapper,
                           KnowledgeMapper knowledgeMapper, FileProcessingMapper processingMapper,
                           ManagedExtractionCache extractionCache, DocumentTextExtractorRegistry extractorRegistry,
                           PlatformTransactionManager transactionManager) {
        this.fileMapper = fileMapper;
        this.knowledgeFileMapper = knowledgeFileMapper;
        this.knowledgeMapper = knowledgeMapper;
        this.processingMapper = processingMapper;
        this.extractionCache = extractionCache;
        this.extractorRegistry = extractorRegistry;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /** Compatibility constructor retained for focused service tests. */
    public FileServiceImpl(FileMapper fileMapper, KnowledgeFileMapper knowledgeFileMapper,
                           KnowledgeMapper knowledgeMapper, FileProcessingMapper processingMapper,
                           ManagedExtractionCache extractionCache,
                           PlatformTransactionManager transactionManager) {
        this(fileMapper, knowledgeFileMapper, knowledgeMapper, processingMapper,
                extractionCache, null, transactionManager);
    }

    @Override
    public Long uploadToKnowledge(MultipartFile file,Long knowledgeId) {
        long tenantId = requireTenantId();
        String fileName = requireSafeFilename(file.getOriginalFilename());
        Path uploadRoot = Path.of(path).toAbsolutePath().normalize();
        String fileType = getFileTypeByExtension(fileName);
        String suffix = "another".equals(fileType) ? "" : "." + fileType;
        Path createdPath = uploadRoot.resolve(Long.toString(tenantId))
                .resolve(Long.toString(knowledgeId))
                .resolve(UUID.randomUUID() + suffix)
                .normalize();
        if (!createdPath.startsWith(uploadRoot)) {
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
        List<FileVo> rows = fileMapper.selectByKnowledgeId(requireTenantId(), knowledgeId);
        for (FileVo row : rows) {
            row.setChunkingCapability(chunkingCapability(row));
        }
        return rows;
    }

    private FileVo.ChunkingCapability chunkingCapability(FileVo row) {
        if (extractorRegistry == null || row.getPath() == null || row.getPath().isBlank()) {
            return new FileVo.ChunkingCapability(false,
                    "暂时无法从该文件提取文本");
        }
        try {
            var capability = extractorRegistry.probe(Path.of(row.getPath()), row.getType());
            return new FileVo.ChunkingCapability(capability.available(), capability.available()
                    ? null : capability.reason());
        } catch (RuntimeException exception) {
            return new FileVo.ChunkingCapability(false,
                    "暂时无法从该文件提取文本");
        }
    }

    @Override
    public List<com.starsea.ai.domain.File> listEnabledByKnowledgeIds(Long tenantId, Set<Long> knowledgeIds) {
        return fileMapper.selectEnabledByKnowledgeIds(tenantId, knowledgeIds);
    }

    @Override
    public boolean deleteFile(Long fileId) {
        long tenantId = requireTenantId();
        if (fileId == null) {
            return false;
        }
        extractionCache.recoverManagedFiles(tenantId, fileId);
        DeletionState[] state = new DeletionState[1];
        try {
            DeletionState committed = transactionTemplate.execute(status -> {
                com.starsea.ai.domain.File file = fileMapper.selectScopedForUpdate(tenantId, fileId);
                if (file == null || !fileId.equals(file.getId())) return null;
                Path uploadRoot = Path.of(path).toAbsolutePath().normalize();
                Path source = Path.of(file.getPath()).toAbsolutePath().normalize();
                if (!source.startsWith(uploadRoot)) {
                    throw new IllegalStateException("文件路径不在受管上传目录中");
                }
                if (!Files.isRegularFile(source, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    throw new IllegalStateException("源文件不存在");
                }
                ManagedExtractionCache.ManagedFileQuarantine cacheQuarantine =
                        extractionCache.quarantineManagedFiles(tenantId, fileId);
                Path sourceQuarantine = uploadRoot.resolve(".deleting")
                        .resolve(Long.toString(tenantId)).resolve(Long.toString(fileId))
                        .resolve(source.getFileName() + ".deleting-" + UUID.randomUUID());
                ManagedExtractionCache.PreparedSourceDeletion sourceDeletion;
                try {
                    sourceDeletion = extractionCache.prepareSourceDeletion(
                            tenantId, fileId, source, sourceQuarantine);
                    DeletionState prepared = new DeletionState(cacheQuarantine, sourceDeletion);
                    state[0] = prepared;
                    extractionCache.movePreparedSource(sourceDeletion);
                    prepared.sourceMoved = true;
                } catch (IOException | RuntimeException exception) {
                    if (state[0] == null) restoreCache(cacheQuarantine, exception);
                    if (exception instanceof RuntimeException runtime) throw runtime;
                    throw new IllegalStateException("源文件隔离失败", exception);
                }
                if (fileMapper.deleteById(fileId) != 1) {
                    throw new IllegalStateException("文件记录删除失败");
                }
                return state[0];
            });
            if (committed == null) return false;
            finalizeCommittedDeletion(committed, tenantId, fileId);
            return true;
        } catch (RuntimeException databaseFailure) {
            restoreDeletion(state[0], databaseFailure);
            throw databaseFailure;
        }
    }

    private void restoreCache(ManagedExtractionCache.ManagedFileQuarantine quarantine,
                              Throwable failure) {
        try {
            quarantine.restore();
        } catch (RuntimeException restoreFailure) {
            failure.addSuppressed(restoreFailure);
        }
    }

    private void restoreDeletion(DeletionState state, Throwable failure) {
        if (state == null) return;
        if (state.sourceMoved) {
            try {
                extractionCache.restorePreparedSource(state.sourceDeletion);
                extractionCache.completeCleanup(state.sourceDeletion.obligationId());
            } catch (IOException | RuntimeException restoreFailure) {
                failure.addSuppressed(restoreFailure);
            }
        } else {
            try {
                extractionCache.completeCleanup(state.sourceDeletion.obligationId());
            } catch (RuntimeException completionFailure) {
                failure.addSuppressed(completionFailure);
            }
        }
        restoreCache(state.cacheQuarantine, failure);
    }

    private void finalizeCommittedDeletion(
            DeletionState state, long tenantId, long fileId) {
        try {
            deleteCommittedSource(state.sourceDeletion.quarantine());
            extractionCache.completeCleanup(state.sourceDeletion.obligationId());
        } catch (IOException | RuntimeException failure) {
            log.warn("Cleanup obligation {} retained for tenant {} file {}",
                    state.sourceDeletion.obligationId(), tenantId, fileId);
        }
        try {
            state.cacheQuarantine.commit();
        } catch (ManagedExtractionCache.CleanupPendingException pending) {
            log.warn("Cleanup obligation {} retained for tenant {} file {}",
                    pending.obligationId(), tenantId, fileId);
        } catch (RuntimeException failure) {
            log.warn("Managed cache cleanup remains pending for tenant {} file {}", tenantId, fileId);
        }
    }

    protected void deleteCommittedSource(Path sourceQuarantine) throws IOException {
        extractionCache.deletePreparedSourcePath(sourceQuarantine);
    }

    private static final class DeletionState {
        private final ManagedExtractionCache.ManagedFileQuarantine cacheQuarantine;
        private final ManagedExtractionCache.PreparedSourceDeletion sourceDeletion;
        private boolean sourceMoved;
        private DeletionState(ManagedExtractionCache.ManagedFileQuarantine cacheQuarantine,
                              ManagedExtractionCache.PreparedSourceDeletion sourceDeletion) {
            this.cacheQuarantine = cacheQuarantine;
            this.sourceDeletion = sourceDeletion;
        }
    }

    private void writePhysicalFile(MultipartFile file, Path uploadRoot, Path createdPath) {
        boolean ownsCreatedPath = false;
        try {
            Files.createDirectories(createdPath.getParent());
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
            extractionCache.deleteUploadedSource(createdPath);
        } catch (IOException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
            log.warn("Failed to remove a newly created upload");
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
