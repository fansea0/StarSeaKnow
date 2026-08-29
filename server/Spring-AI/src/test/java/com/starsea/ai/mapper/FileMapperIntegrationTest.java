package com.starsea.ai.mapper;

import com.starsea.ai.auth.AuthContext;
import com.starsea.ai.domain.File;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class FileMapperIntegrationTest {

    @Autowired
    private FileMapper mapper;

    @Autowired
    private JdbcTemplate jdbc;

    private Long tenantId;
    private Long fileId;
    private Long otherTenantId;
    private final List<Long> knowledgeIds = new java.util.ArrayList<>();
    private final List<Long> fileIds = new java.util.ArrayList<>();

    @AfterEach
    void tearDown() {
        AuthContext.clear();
        for (Long id : fileIds) {
            jdbc.update("DELETE FROM knowledge_file WHERE file_id = ?", id);
            jdbc.update("DELETE FROM file WHERE id = ?", id);
        }
        for (Long id : knowledgeIds) {
            jdbc.update("DELETE FROM knowledge WHERE id = ?", id);
        }
        if (tenantId != null) {
            jdbc.update("DELETE FROM tenant WHERE id = ?", tenantId);
        }
        if (otherTenantId != null) {
            jdbc.update("DELETE FROM tenant WHERE id = ?", otherTenantId);
        }
    }

    @Test
    void selectBatchIdsMaterializesPublicIdWithGeneratedProjection() {
        tenantId = jdbc.queryForObject("""
                INSERT INTO tenant (code, name) VALUES (?, 'File Mapper Test')
                RETURNING id
                """, Long.class, "file-mapper-" + UUID.randomUUID());
        UUID publicId = UUID.randomUUID();
        fileId = jdbc.queryForObject("""
                INSERT INTO file (tenant_id, public_id, file_name, size, status, type, path, embedding_status)
                VALUES (?, ?, 'mapper.pdf', 10, 1, 'pdf', '/private/mapper.pdf', 1)
                RETURNING id
                """, Long.class, tenantId, publicId);
        fileIds.add(fileId);
        AuthContext.set(new AuthContext(AuthContext.Kind.BUSINESS, 1L, tenantId, "tenant_admin", "test"));

        List<File> rows = mapper.selectBatchIds(List.of(fileId));

        assertThat(rows).singleElement().satisfies(file -> {
            assertThat(file.getId()).isEqualTo(fileId);
            assertThat(file.getPublicId()).isEqualTo(publicId);
            assertThat(file.getFileName()).isEqualTo("mapper.pdf");
        });
    }

    @Test
    void selectEnabledByKnowledgeIdsRestrictsTenantScopeKnowledgeScopeAndStatus() {
        tenantId = insertTenant("eligible-owner");
        otherTenantId = insertTenant("eligible-foreign");
        Long allowedKnowledgeId = insertKnowledge(tenantId, "allowed");
        Long unauthorizedKnowledgeId = insertKnowledge(tenantId, "unauthorized");
        Long foreignKnowledgeId = insertKnowledge(otherTenantId, "foreign");
        Long enabledAllowedId = insertFile(tenantId, "enabled.pdf", 1);
        Long disabledAllowedId = insertFile(tenantId, "disabled.pdf", 0);
        Long enabledUnauthorizedId = insertFile(tenantId, "unauthorized.pdf", 1);
        Long enabledForeignId = insertFile(otherTenantId, "foreign.pdf", 1);
        link(tenantId, allowedKnowledgeId, enabledAllowedId);
        link(tenantId, allowedKnowledgeId, disabledAllowedId);
        link(tenantId, unauthorizedKnowledgeId, enabledUnauthorizedId);
        link(otherTenantId, foreignKnowledgeId, enabledForeignId);
        AuthContext.set(new AuthContext(AuthContext.Kind.BUSINESS, 1L, tenantId, "tenant_admin", "test"));

        List<File> rows = mapper.selectEnabledByKnowledgeIds(tenantId, Set.of(allowedKnowledgeId));

        assertThat(rows).extracting(File::getId).containsExactly(enabledAllowedId);
        assertThat(rows).allSatisfy(file -> {
            assertThat(file.getStatus()).isEqualTo(1);
            assertThat(file.getPublicId()).isNotNull();
        });
    }

    private Long insertTenant(String prefix) {
        return jdbc.queryForObject("""
                INSERT INTO tenant (code, name) VALUES (?, 'File Scope Test')
                RETURNING id
                """, Long.class, prefix + "-" + UUID.randomUUID());
    }

    private Long insertKnowledge(Long ownerTenantId, String name) {
        Long id = jdbc.queryForObject("""
                INSERT INTO knowledge (tenant_id, name) VALUES (?, ?)
                RETURNING id
                """, Long.class, ownerTenantId, name);
        knowledgeIds.add(id);
        return id;
    }

    private Long insertFile(Long ownerTenantId, String name, int status) {
        Long id = jdbc.queryForObject("""
                INSERT INTO file (tenant_id, file_name, size, status, type, path, embedding_status)
                VALUES (?, ?, 10, ?, 'pdf', ?, 1)
                RETURNING id
                """, Long.class, ownerTenantId, name, status, "/private/" + name);
        fileIds.add(id);
        return id;
    }

    private void link(Long ownerTenantId, Long knowledgeId, Long linkedFileId) {
        jdbc.update("""
                INSERT INTO knowledge_file (tenant_id, knowledge_id, file_id)
                VALUES (?, ?, ?)
                """, ownerTenantId, knowledgeId, linkedFileId);
    }
}
