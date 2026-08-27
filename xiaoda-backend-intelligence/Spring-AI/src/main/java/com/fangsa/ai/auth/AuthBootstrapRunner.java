package com.fangsa.ai.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Base64;

@Component
public class AuthBootstrapRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AuthBootstrapRunner.class);

    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;

    public AuthBootstrapRunner(JdbcTemplate jdbc, PasswordEncoder encoder) {
        this.jdbc = jdbc; this.encoder = encoder;
    }

    @Override
    public void run(ApplicationArguments args) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM platform_admin", Integer.class);
        if (count != null && count > 0) {
            log.info("AuthBootstrap: platform_admin already exists, skipping seed.");
            return;
        }
        byte[] buf = new byte[16];
        new SecureRandom().nextBytes(buf);
        String pwd = Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
        String hash = encoder.hash(pwd);
        jdbc.update("INSERT INTO platform_admin (username, password_hash) VALUES (?, ?)", "su", hash);
        log.warn("====================================================================");
        log.warn("AuthBootstrap: seeded platform admin 'su' with initial password:");
        log.warn("    {}", pwd);
        log.warn("LOG THIS PASSWORD NOW and CHANGE IT IMMEDIATELY after first login.");
        log.warn("====================================================================");
    }
}