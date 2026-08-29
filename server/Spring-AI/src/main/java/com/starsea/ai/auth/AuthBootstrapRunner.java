package com.starsea.ai.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class AuthBootstrapRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AuthBootstrapRunner.class);

    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final String username;
    private final String password;

    public AuthBootstrapRunner(JdbcTemplate jdbc, PasswordEncoder encoder,
                               @Value("${platform.bootstrap.username:su}") String username,
                               @Value("${platform.bootstrap.password:ChangeMe!123}") String password) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.username = username;
        this.password = password;
    }

    @Override
    public void run(ApplicationArguments args) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM platform_admin", Integer.class);
        if (count != null && count > 0) {
            log.info("AuthBootstrap: platform_admin already exists, skipping seed.");
            return;
        }
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            throw new IllegalStateException("platform.bootstrap.username and platform.bootstrap.password must not be blank");
        }
        jdbc.update("INSERT INTO platform_admin (username, password_hash, must_change_password) VALUES (?, ?, TRUE)",
                username, encoder.hash(password));
        log.warn("AuthBootstrap: seeded platform administrator '{}'; change the configured initial password immediately.", username);
    }
}
