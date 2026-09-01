package com.starsea.ai.service.impl;

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
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FileServiceImplTest {

    @TempDir
    Path uploadDirectory;

    private FileMapper fileMapper;
    private KnowledgeFileMapper knowledgeFileMapper;
    private KnowledgeMapper knowledgeMapper;
    private FileProcessingMapper processingMapper;
    private TestTransactionManager transactionManager;
    private FileServiceImpl service;

    @BeforeEach
    void setUp() {
        fileMapper = mock(FileMapper.class);
        knowledgeFileMapper = mock(KnowledgeFileMapper.class);
        knowledgeMapper = mock(KnowledgeMapper.class);
        processingMapper = mock(FileProcessingMapper.class);
        transactionManager = new TestTransactionManager();
        service = new FileServiceImpl(fileMapper, knowledgeFileMapper, knowledgeMapper,
                processingMapper, transactionManager);
        ReflectionTestUtils.setField(service, "path", uploadDirectory.toString());
        AuthContext.set(new AuthContext(AuthContext.Kind.BUSINESS, 7L, 1L, "tenant_admin", "jti-1"));
    }

    @AfterEach
    void clearContext() {
        AuthContext.clear();
    }

    @Test
    void knowledge_upload_creates_file_relation_and_uploaded_processing_row() {
        Knowledge knowledge = new Knowledge();
        knowledge.setId(10L);
        when(knowledgeMapper.selectById(10L)).thenAnswer(invocation -> {
            assertFalse(transactionManager.isActive());
            assertEquals(1, storedFileCount());
            return knowledge;
        });
        doAnswer(invocation -> {
            assertTrue(transactionManager.isActive());
            com.starsea.ai.domain.File file = invocation.getArgument(0);
            file.setId(20L);
            return 1;
        }).when(fileMapper).insert(any(com.starsea.ai.domain.File.class));
        when(knowledgeFileMapper.insert(any(KnowledgeFile.class))).thenAnswer(invocation -> {
            assertTrue(transactionManager.isActive());
            return 1;
        });
        when(processingMapper.insert(any(FileProcessing.class))).thenAnswer(invocation -> {
            assertTrue(transactionManager.isActive());
            return 1;
        });

        long fileId = service.uploadToKnowledge(markdown("guide.md"), 10L);

        assertEquals(20L, fileId);
        assertEquals(1, storedFileCount());
        var order = inOrder(fileMapper, knowledgeFileMapper, processingMapper);
        order.verify(fileMapper).insert(any(com.starsea.ai.domain.File.class));
        order.verify(knowledgeFileMapper).insert(any(KnowledgeFile.class));
        order.verify(processingMapper).insert(org.mockito.ArgumentMatchers.argThat(processing ->
                processing.getFileId().equals(20L)
                        && processing.getTenantId().equals(1L)
                        && processing.getKnowledgeId().equals(10L)
                        && processing.getPipelineState().equals(PipelineState.UPLOADED.code())
                        && processing.getProgress().equals(0)
                        && processing.getLockVersion().equals(0)));
        assertEquals(1, transactionManager.commits());
        assertEquals(0, transactionManager.rollbacks());
    }

    @Test
    void identical_original_names_across_tenants_and_knowledge_bases_use_distinct_physical_paths() {
        Knowledge knowledge = new Knowledge();
        knowledge.setId(10L);
        when(knowledgeMapper.selectById(org.mockito.ArgumentMatchers.anyLong())).thenReturn(knowledge);
        AtomicLong ids = new AtomicLong(20L);
        List<com.starsea.ai.domain.File> rows = new ArrayList<>();
        doAnswer(invocation -> {
            com.starsea.ai.domain.File row = invocation.getArgument(0);
            row.setId(ids.getAndIncrement());
            rows.add(row);
            return 1;
        }).when(fileMapper).insert(any(com.starsea.ai.domain.File.class));
        when(knowledgeFileMapper.insert(any(KnowledgeFile.class))).thenReturn(1);
        when(processingMapper.insert(any(FileProcessing.class))).thenReturn(1);

        service.uploadToKnowledge(markdown("guide.md"), 10L);
        AuthContext.set(new AuthContext(AuthContext.Kind.BUSINESS, 8L, 2L,
                "tenant_admin", "jti-2"));
        service.uploadToKnowledge(markdown("guide.md"), 11L);

        assertEquals(List.of("guide.md", "guide.md"), rows.stream()
                .map(com.starsea.ai.domain.File::getFileName).toList());
        assertEquals(2, rows.stream().map(com.starsea.ai.domain.File::getPath).distinct().count());
        assertTrue(rows.stream().allMatch(row -> Files.exists(Path.of(row.getPath()))));
        assertTrue(rows.get(0).getPath().contains("1" + java.io.File.separator + "10"));
        assertTrue(rows.get(1).getPath().contains("2" + java.io.File.separator + "11"));
    }

    @Test
    void commit_failure_rolls_back_all_three_inserts_and_removes_only_the_new_file() {
        transactionManager.failCommit();
        Knowledge knowledge = new Knowledge();
        knowledge.setId(10L);
        when(knowledgeMapper.selectById(10L)).thenAnswer(invocation -> {
            assertFalse(transactionManager.isActive());
            assertEquals(1, storedFileCount());
            return knowledge;
        });
        doAnswer(invocation -> {
            assertTrue(transactionManager.isActive());
            com.starsea.ai.domain.File file = invocation.getArgument(0);
            file.setId(20L);
            return 1;
        }).when(fileMapper).insert(any(com.starsea.ai.domain.File.class));
        when(knowledgeFileMapper.insert(any(KnowledgeFile.class))).thenAnswer(invocation -> {
            assertTrue(transactionManager.isActive());
            return 1;
        });
        when(processingMapper.insert(any(FileProcessing.class))).thenAnswer(invocation -> {
            assertTrue(transactionManager.isActive());
            return 1;
        });

        assertThrows(TransactionSystemException.class,
                () -> service.uploadToKnowledge(markdown("commit-failed.md"), 10L));

        assertEquals(0, storedFileCount());
        verify(fileMapper).insert(any(com.starsea.ai.domain.File.class));
        verify(knowledgeFileMapper).insert(any(KnowledgeFile.class));
        verify(processingMapper).insert(any(FileProcessing.class));
        assertEquals(1, transactionManager.commits());
        assertEquals(1, transactionManager.rollbacks());
    }

    @Test
    void database_failure_removes_only_the_new_physical_file() throws Exception {
        Path unrelated = uploadDirectory.resolve("keep.txt");
        Files.writeString(unrelated, "keep");
        Knowledge knowledge = new Knowledge();
        knowledge.setId(10L);
        when(knowledgeMapper.selectById(10L)).thenReturn(knowledge);
        doAnswer(invocation -> {
            com.starsea.ai.domain.File file = invocation.getArgument(0);
            file.setId(20L);
            return 1;
        }).when(fileMapper).insert(any(com.starsea.ai.domain.File.class));
        when(knowledgeFileMapper.insert(any(KnowledgeFile.class))).thenReturn(1);
        when(processingMapper.insert(any(FileProcessing.class))).thenThrow(new IllegalStateException("db failed"));

        assertThrows(IllegalStateException.class,
                () -> service.uploadToKnowledge(markdown("failed.md"), 10L));

        assertFalse(Files.exists(uploadDirectory.resolve("failed.md")));
        assertTrue(Files.exists(unrelated));
    }

    @Test
    void preexisting_original_name_is_never_used_or_deleted() throws Exception {
        Path existing = uploadDirectory.resolve("existing.md");
        Files.writeString(existing, "original content");

        assertThrows(IllegalArgumentException.class,
                () -> service.uploadToKnowledge(markdown("existing.md"), 10L));

        assertTrue(Files.exists(existing));
        assertEquals("original content", Files.readString(existing));
    }

    @Test
    void input_stream_failure_never_deletes_a_preexisting_target() throws Exception {
        Path existing = uploadDirectory.resolve("broken.md");
        Files.writeString(existing, "original content");
        MultipartFile broken = mock(MultipartFile.class);
        when(broken.getOriginalFilename()).thenReturn("broken.md");
        when(broken.getInputStream()).thenThrow(new IOException("stream unavailable"));

        assertThrows(IllegalStateException.class,
                () -> service.uploadToKnowledge(broken, 10L));

        assertTrue(Files.exists(existing));
        assertEquals("original content", Files.readString(existing));
    }

    @Test
    void missing_processing_insert_rejects_upload_and_removes_physical_file() {
        Knowledge knowledge = new Knowledge();
        knowledge.setId(10L);
        when(knowledgeMapper.selectById(10L)).thenReturn(knowledge);
        doAnswer(invocation -> {
            com.starsea.ai.domain.File file = invocation.getArgument(0);
            file.setId(20L);
            return 1;
        }).when(fileMapper).insert(any(com.starsea.ai.domain.File.class));
        when(knowledgeFileMapper.insert(any(KnowledgeFile.class))).thenReturn(1);
        when(processingMapper.insert(any(FileProcessing.class))).thenReturn(0);

        assertThrows(IllegalStateException.class,
                () -> service.uploadToKnowledge(markdown("missing-row.md"), 10L));

        assertFalse(Files.exists(uploadDirectory.resolve("missing-row.md")));
    }

    @Test
    void file_list_uses_current_tenant_and_preserves_processing_fields() {
        FileVo row = new FileVo();
        row.setId(20L);
        row.setPipelineState(PipelineState.CHUNKING.code());
        row.setProgress(35);
        row.setProcessingError("parser failed");
        when(fileMapper.selectByKnowledgeId(1L, 10L)).thenReturn(List.of(row));

        List<FileVo> files = service.listByKnowledgeId(10L);

        assertEquals(1, files.size());
        assertEquals(PipelineState.CHUNKING.code(), files.get(0).getPipelineState());
        assertEquals(35, files.get(0).getProgress());
        assertEquals("parser failed", files.get(0).getProcessingError());
        verify(fileMapper).selectByKnowledgeId(1L, 10L);
    }

    @Test
    void mapper_query_joins_processing_with_explicit_tenant_and_knowledge_scope() throws Exception {
        Configuration configuration = new Configuration();
        try (InputStream input = getClass().getClassLoader().getResourceAsStream("mapper/FileMapper.xml")) {
            new XMLMapperBuilder(input, configuration, "mapper/FileMapper.xml",
                    configuration.getSqlFragments()).parse();
        }
        MappedStatement statement = configuration.getMappedStatement(
                "com.starsea.ai.mapper.FileMapper.selectByKnowledgeId");
        BoundSql boundSql = statement.getBoundSql(Map.of("tenantId", 1L, "knowledgeId", 10L));
        String sql = boundSql.getSql().replaceAll("\\s+", " ").trim();

        assertTrue(sql.contains("JOIN knowledge_file kf ON kf.file_id = f.id AND kf.tenant_id = ? AND kf.knowledge_id = ?"));
        assertTrue(sql.contains("JOIN file_processing fp ON fp.file_id = f.id AND fp.tenant_id = ? AND fp.knowledge_id = ?"));
        assertTrue(sql.contains("WHERE f.tenant_id = ?"));
        assertEquals(FileVo.class, statement.getResultMaps().get(0).getType());
        assertTrue(statement.getResultMaps().get(0).getResultMappings().stream()
                .map(mapping -> mapping.getProperty())
                .toList()
                .containsAll(List.of("pipelineState", "progress", "processingError")));
    }

    private MockMultipartFile markdown(String filename) {
        return new MockMultipartFile("file", filename, "text/markdown",
                "# Guide\n\nBody".getBytes(StandardCharsets.UTF_8));
    }

    private long storedFileCount() {
        try (var paths = Files.walk(uploadDirectory)) {
            return paths.filter(Files::isRegularFile).count();
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static final class TestTransactionManager extends AbstractPlatformTransactionManager {
        private boolean active;
        private boolean failCommit;
        private int commits;
        private int rollbacks;

        private TestTransactionManager() {
            setRollbackOnCommitFailure(true);
        }

        void failCommit() {
            failCommit = true;
        }

        boolean isActive() {
            return active;
        }

        int commits() {
            return commits;
        }

        int rollbacks() {
            return rollbacks;
        }

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            active = true;
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            commits++;
            if (failCommit) {
                throw new TransactionSystemException("deterministic commit failure");
            }
            active = false;
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            rollbacks++;
            active = false;
        }

        @Override
        protected void doCleanupAfterCompletion(Object transaction) {
            active = false;
        }
    }
}
