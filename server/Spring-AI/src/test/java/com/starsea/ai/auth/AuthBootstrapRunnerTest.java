package com.starsea.ai.auth;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthBootstrapRunnerTest {

    @Test
    void createsConfiguredPlatformAdminWithBcryptPasswordWhenNoAdminExists() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        PasswordEncoder encoder = new BcryptPasswordEncoder();
        when(jdbc.queryForObject("SELECT COUNT(*) FROM platform_admin", Integer.class)).thenReturn(0);

        new AuthBootstrapRunner(jdbc, encoder, "bootstrap-su", "ChangeMe!123").run(null);

        verify(jdbc).update(eq("INSERT INTO platform_admin (username, password_hash, must_change_password) VALUES (?, ?, TRUE)"),
                eq("bootstrap-su"), org.mockito.ArgumentMatchers.<String>argThat(hash -> encoder.matches("ChangeMe!123", hash)));
    }

    @Test
    void leavesExistingPlatformAdminUntouched() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject("SELECT COUNT(*) FROM platform_admin", Integer.class)).thenReturn(1);

        new AuthBootstrapRunner(jdbc, new BcryptPasswordEncoder(), "bootstrap-su", "ChangeMe!123").run(null);

        verify(jdbc).queryForObject("SELECT COUNT(*) FROM platform_admin", Integer.class);
        verify(jdbc, org.mockito.Mockito.never()).update(anyString(), org.mockito.ArgumentMatchers.<Object[]>any());
    }
}
