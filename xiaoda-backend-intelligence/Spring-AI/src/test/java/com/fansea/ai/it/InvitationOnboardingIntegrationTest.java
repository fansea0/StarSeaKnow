package com.fansea.ai.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fansea.ai.auth.AuthErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InvitationOnboardingIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    JdbcTemplate jdbc;

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM platform_invitation WHERE code LIKE 'onboarding-%'");
        jdbc.update("DELETE FROM refresh_token WHERE user_id IN (SELECT id FROM app_user WHERE username LIKE 'onboarding-%')");
        jdbc.update("DELETE FROM app_user WHERE username LIKE 'onboarding-%'");
        jdbc.update("DELETE FROM tenant WHERE code LIKE 'onboarding-%'");
    }

    @BeforeEach
    void resetFixtures() {
        cleanUp();
    }

    @Test
    void registrationConsumesInvitationCreatesTenantAndLogsIn() throws Exception {
        String code = activeInvitation();

        Response response = post("/auth/register", Map.of(
                "inviteCode", code,
                "username", "onboarding-ocean-admin",
                "password", "Strong!123",
                "confirmPassword", "Strong!123"));

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body().at("/data/user/username").asText()).isEqualTo("onboarding-ocean-admin");
        assertThat(jdbc.queryForObject("""
                SELECT t.name FROM tenant t JOIN app_user u ON u.tenant_id = t.id
                WHERE u.username = 'onboarding-ocean-admin'
                """, String.class)).isEqualTo("onboarding-ocean-admin 的工作区");
        assertThat(jdbc.queryForObject("SELECT status FROM platform_invitation WHERE code = ?", String.class, code))
                .isEqualTo("USED");
    }

    @Test
    void registrationRejectsUnavailableInvitationAndMismatchedPasswords() throws Exception {
        String disabled = invitation("DISABLED", OffsetDateTime.now().minusHours(1), OffsetDateTime.now().plusHours(1));
        String expired = invitation("ACTIVE", OffsetDateTime.now().minusHours(2), OffsetDateTime.now().minusHours(1));
        String active = activeInvitation();

        assertThat(post("/auth/register", request(disabled, "onboarding-disabled", "Strong!123", "Strong!123")).status()).isEqualTo(410);
        assertThat(post("/auth/register", request(expired, "onboarding-expired", "Strong!123", "Strong!123")).status()).isEqualTo(410);
        assertThat(post("/auth/register", request(active, "onboarding-mismatch", "Strong!123", "Different!123")).status()).isEqualTo(400);

        assertThat(post("/auth/register", request(active, "onboarding-reused", "Strong!123", "Strong!123")).status()).isEqualTo(200);
        assertThat(post("/auth/register", request(active, "onboarding-reused-again", "Strong!123", "Strong!123")).status()).isEqualTo(410);
    }

    @Test
    void tenantUserCanLogInWithUsernameAndPasswordOnly() throws Exception {
        String username = "onboarding-login-admin";
        Response registration = post("/auth/register", request(activeInvitation(), username, "Strong!123", "Strong!123"));
        assertThat(registration.status()).isEqualTo(200);

        Response login = post("/auth/login", Map.of("username", username, "password", "Strong!123"));

        assertThat(login.status()).isEqualTo(200);
        assertThat(login.body().at("/data/user/username").asText()).isEqualTo(username);
    }

    @Test
    void duplicateUsernameRegistrationReturnsConflictWithoutConsumingSecondInvitation() throws Exception {
        String username = "onboarding-duplicate-admin";
        assertThat(post("/auth/register", request(activeInvitation(), username, "Strong!123", "Strong!123")).status())
                .isEqualTo(200);
        String secondInvitation = activeInvitation();
        int tenantsBefore = jdbc.queryForObject("SELECT count(*) FROM tenant", Integer.class);
        int usersBefore = jdbc.queryForObject("SELECT count(*) FROM app_user", Integer.class);

        Response duplicate = post("/auth/register", request(secondInvitation, username, "Strong!123", "Strong!123"));

        assertThat(duplicate.status()).isEqualTo(409);
        assertThat(duplicate.body().at("/code").asInt()).isEqualTo(AuthErrorCode.USERNAME_CONFLICT.code());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tenant", Integer.class)).isEqualTo(tenantsBefore);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_user", Integer.class)).isEqualTo(usersBefore);
        assertThat(jdbc.queryForObject("SELECT status FROM platform_invitation WHERE code = ?", String.class, secondInvitation))
                .isEqualTo("ACTIVE");
    }

    private Map<String, String> request(String inviteCode, String username, String password, String confirmPassword) {
        return Map.of("inviteCode", inviteCode, "username", username, "password", password, "confirmPassword", confirmPassword);
    }

    private String activeInvitation() {
        return invitation("ACTIVE", OffsetDateTime.now().minusMinutes(1), OffsetDateTime.now().plusHours(1));
    }

    private String invitation(String status, OffsetDateTime validFrom, OffsetDateTime validUntil) {
        String code = "onboarding-" + System.nanoTime();
        jdbc.update("""
                INSERT INTO platform_invitation (code, status, valid_from, valid_until, created_by)
                VALUES (?, ?, ?, ?, (SELECT id FROM platform_admin WHERE username = 'su'))
                """, code, status, validFrom, validUntil);
        return code;
    }

    private Response post(String path, Map<String, String> body) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        var response = new TestRestTemplate().exchange(
                "http://localhost:" + port + path,
                HttpMethod.POST, new HttpEntity<>(objectMapper.writeValueAsString(body), headers), String.class);
        return new Response(response.getStatusCode().value(), objectMapper.readTree(response.getBody()));
    }

    private record Response(int status, JsonNode body) { }
}
