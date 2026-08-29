package com.starsea.ai.mapper;

import com.starsea.ai.domain.PlatformInvitation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.core.io.FileSystemResource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Sql(scripts = "file:src/main/resources/db/V2__invitation_based_tenant_onboarding.sql")
class PlatformInvitationMapperTest {

    @Autowired
    private PlatformInvitationMapper mapper;

    @Autowired
    private JdbcTemplate jdbc;

    private long disabledId;
    private long expiredId;
    private long futureId;
    private long usedId;
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
        futureId = insertInvitation("ACTIVE", OffsetDateTime.now().plusHours(1), OffsetDateTime.now().plusHours(2));
        usedId = insertInvitation("USED", OffsetDateTime.now().minusHours(1), OffsetDateTime.now().plusHours(1));
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
        PlatformInvitation consumed = mapper.selectById(invitation.getId());
        assertThat(consumed.getStatus()).isEqualTo("USED");
        assertThat(consumed.getUsedTenantId()).isEqualTo(usedTenantId);
        assertThat(consumed.getUsedUserId()).isEqualTo(usedUserId);
        assertThat(mapper.consumeIfAvailable(invitation.getId(), OffsetDateTime.now(), usedTenantId, usedUserId)).isZero();
    }

    @Test
    void consumeIfAvailable_rejectsDisabledOrExpiredInvitation() {
        assertThat(mapper.consumeIfAvailable(disabledId, OffsetDateTime.now(), usedTenantId, usedUserId)).isZero();
        assertThat(mapper.consumeIfAvailable(expiredId, OffsetDateTime.now(), usedTenantId, usedUserId)).isZero();
        assertThat(mapper.consumeIfAvailable(futureId, OffsetDateTime.now(), usedTenantId, usedUserId)).isZero();
        assertThat(mapper.consumeIfAvailable(usedId, OffsetDateTime.now(), usedTenantId, usedUserId)).isZero();
    }

    @Test
    void migrationRejectsLegacyDuplicatesWithoutChangingUsernames() {
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            try {
                try (var statement = connection.createStatement()) {
                    statement.execute("CREATE TEMP TABLE app_user (id BIGINT, username VARCHAR(64))");
                    statement.execute("INSERT INTO app_user (id, username) VALUES (2, 'admin'), (3, 'admin'), (4, 'admin-3')");
                }

                assertThatThrownBy(() -> ScriptUtils.executeSqlScript(
                        connection,
                        new FileSystemResource("src/main/resources/db/V2__invitation_based_tenant_onboarding.sql")))
                        .hasMessageContaining("Cannot migrate app_user to globally unique usernames");

                try (var statement = connection.createStatement();
                     var result = statement.executeQuery("SELECT username FROM app_user ORDER BY id")) {
                    result.next();
                    assertThat(result.getString(1)).isEqualTo("admin");
                    result.next();
                    assertThat(result.getString(1)).isEqualTo("admin");
                    result.next();
                    assertThat(result.getString(1)).isEqualTo("admin-3");
                }
            } finally {
                try (var statement = connection.createStatement()) {
                    statement.execute("DROP TABLE IF EXISTS app_user");
                }
            }
            return null;
        });
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
