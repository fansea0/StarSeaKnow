package com.fansea.ai.mapper;

import com.fansea.ai.domain.PlatformInvitation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Sql(scripts = "file:src/main/resources/db/V2__invitation_based_tenant_onboarding.sql")
class PlatformInvitationMapperTest {

    @Autowired
    private PlatformInvitationMapper mapper;

    @Autowired
    private JdbcTemplate jdbc;

    private long disabledId;
    private long expiredId;
    private long usedTenantId;
    private long usedUserId;

    @BeforeEach
    void setUp() {
        cleanFixtureRows();
        usedTenantId = jdbc.queryForObject("""
                INSERT INTO tenant (code, name) VALUES (?, ?)
                RETURNING id
                """, Long.class, "test-tenant-" + System.nanoTime(), "Test Tenant");
        usedUserId = jdbc.queryForObject("""
                INSERT INTO app_user (tenant_id, username, password_hash, role)
                VALUES (?, ?, 'hash', 'tenant_admin')
                RETURNING id
                """, Long.class, usedTenantId, "test-user-" + System.nanoTime());
        disabledId = insertInvitation("DISABLED", OffsetDateTime.now().minusHours(1), OffsetDateTime.now().plusHours(1));
        expiredId = insertInvitation("ACTIVE", OffsetDateTime.now().minusHours(2), OffsetDateTime.now().minusHours(1));
    }

    @AfterEach
    void tearDown() {
        cleanFixtureRows();
    }

    private void cleanFixtureRows() {
        jdbc.update("DELETE FROM platform_invitation WHERE code LIKE 'test-%'");
        jdbc.update("DELETE FROM app_user WHERE username LIKE 'test-user-%'");
        jdbc.update("DELETE FROM tenant WHERE code LIKE 'test-tenant-%'");
    }

    @Test
    void consumeIfAvailable_updatesOneActiveUnusedInvitationOnly() {
        PlatformInvitation invitation = insertActiveInvitation();

        int updated = mapper.consumeIfAvailable(invitation.getId(), OffsetDateTime.now(), usedTenantId, usedUserId);

        assertThat(updated).isEqualTo(1);
        assertThat(mapper.selectById(invitation.getId()).getStatus()).isEqualTo("USED");
    }

    @Test
    void consumeIfAvailable_rejectsDisabledOrExpiredInvitation() {
        assertThat(mapper.consumeIfAvailable(disabledId, OffsetDateTime.now(), usedTenantId, usedUserId)).isZero();
        assertThat(mapper.consumeIfAvailable(expiredId, OffsetDateTime.now(), usedTenantId, usedUserId)).isZero();
    }

    private PlatformInvitation insertActiveInvitation() {
        long id = insertInvitation("ACTIVE", OffsetDateTime.now().minusHours(1), OffsetDateTime.now().plusHours(1));
        return mapper.selectById(id);
    }

    private long insertInvitation(String status, OffsetDateTime validFrom, OffsetDateTime validUntil) {
        return jdbc.queryForObject("""
                INSERT INTO platform_invitation (code, status, valid_from, valid_until, created_by)
                VALUES (?, ?, ?, ?, (SELECT id FROM platform_admin WHERE username = 'su'))
                RETURNING id
                """, Long.class, "test-" + System.nanoTime(), status, validFrom, validUntil);
    }
}
