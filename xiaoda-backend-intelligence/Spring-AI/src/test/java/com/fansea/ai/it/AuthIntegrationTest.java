package com.fansea.ai.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.test.context.TestPropertySource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * End-to-end integration test for the platform auth + multi-tenant flow.
 *
 * Sequence: register from a platform invitation -> /agent/list (scope check) ->
 *           /auth/me (roundtrip) -> /auth/refresh (cookie rotation).
 *
 * Note: package is the lowercase {@code fansea} legacy package (preserved per Task 6 ruling).
 * The brief originally said {@code com.fansea.ai.it} but that directory does not exist;
 * this test mirrors the existing {@code com.fansea.ai} test layout.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "spring.main.banner-mode=off")
class AuthIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    ObjectMapper om;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    com.fansea.ai.auth.PasswordEncoder encoder;

    @BeforeEach
    void resetSu() {
        // Reset platform su password to a known value via the project's PasswordEncoder
        // (not BCrypt.hashpw directly — see Task 19 ruling on the abstraction).
        jdbc.update("UPDATE platform_admin SET password_hash = ? WHERE username='su'",
                encoder.hash("su-test-pw"));
        jdbc.update("DELETE FROM platform_invitation WHERE code LIKE 'auth-it-%'");
        jdbc.update("DELETE FROM invite");
        jdbc.update("DELETE FROM refresh_token");
        jdbc.update("DELETE FROM app_user WHERE username <> 'admin' OR id NOT IN (SELECT id FROM app_user WHERE username='admin')");
    }

    @Test
    void fullFlow_registration_login_listAgentsIsTenantScoped() throws Exception {
        String inviteCode = "auth-it-" + System.nanoTime();
        jdbc.update("""
                INSERT INTO platform_invitation (code, status, valid_from, valid_until, created_by)
                VALUES (?, 'ACTIVE', CURRENT_TIMESTAMP - INTERVAL '1 minute', CURRENT_TIMESTAMP + INTERVAL '1 hour',
                        (SELECT id FROM platform_admin WHERE username = 'su'))
                """, inviteCode);

        Resp register = http("/auth/register",
                Map.of("inviteCode", inviteCode, "username", "auth-it-admin",
                        "password", "Pw!12345", "confirmPassword", "Pw!12345"), null, null);
        String ac = register.body.get("data").get("accessToken").asText();
        String refreshCookie = register.headers.getFirst("Set-Cookie").split(";")[0];
        long tenantId = register.body.get("data").get("user").get("tenantId").asLong();

        // 2. /agent/list with the tenant token (GET /agent/list — verifies scope filter)
        Resp list = http("/agent/list", HttpMethod.GET, null,
                Map.of("Authorization", "Bearer " + ac), null);
        assertEquals(200, list.body.get("code").asInt());

        // 3. /auth/me roundtrip — UserView exposes tenantId via record serialization
        Resp me = http("/auth/me", HttpMethod.GET, null,
                Map.of("Authorization", "Bearer " + ac), null);
        assertEquals(tenantId, me.body.get("data").get("user").get("tenantId").asLong());

        // 4. /auth/refresh — cookie rotation
        Resp ref = http("/auth/refresh", null, null, refreshCookie);
        assertEquals(200, ref.body.get("code").asInt());
        String newCookie = ref.headers.getFirst("Set-Cookie").split(";")[0];
        assertNotEquals(refreshCookie, newCookie);
    }

    /**
     * Response wrapper used by {@link #http}. Named-field record so callers
     * access via {@code .body.get(...)} and {@code .headers.getFirst(...)}
     * (NOT direct {@code .get(...)} on the record itself).
     */
    private record Resp(int status, JsonNode body, HttpHeaders headers) {}

    /** 5-arg overload — full control over HTTP method (used for GET endpoints). */
    private Resp http(String path, HttpMethod method,
                      Object body, Map<String, String> extraHeaders, String cookie) throws Exception {
        HttpHeaders h = new HttpHeaders();
        if (body != null) {
            h.setContentType(MediaType.APPLICATION_JSON);
        }
        if (extraHeaders != null) {
            h.setAll(extraHeaders);
        }
        if (cookie != null) {
            h.add("Cookie", cookie);
        }
        HttpEntity<?> req = body == null
                ? new HttpEntity<Void>(h)
                : new HttpEntity<>(om.writeValueAsString(body), h);
        var resp = new TestRestTemplate().exchange(
                "http://localhost:" + port + path, method, req, String.class);
        return new Resp(
                resp.getStatusCode().value(),
                om.readTree(resp.getBody() == null ? "{}" : resp.getBody()),
                resp.getHeaders());
    }

    /** 4-arg overload — wraps to POST (used for login, accept-invite, refresh). */
    private Resp http(String path, Object body, Map<String, String> extraHeaders, String cookie) throws Exception {
        return http(path, HttpMethod.POST, body, extraHeaders, cookie);
    }
}
