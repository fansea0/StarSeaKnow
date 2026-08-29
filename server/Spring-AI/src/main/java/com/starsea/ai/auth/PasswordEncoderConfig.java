package com.starsea.ai.auth;

import com.starsea.ai.mapper.TenantMapper;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PasswordEncoderConfig {
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BcryptPasswordEncoder();
    }

    @Bean
    public FilterRegistrationBean<JwtAuthFilter> jwtAuthFilterRegistration(JwtService jwtService, TenantMapper tenants) {
        FilterRegistrationBean<JwtAuthFilter> reg = new FilterRegistrationBean<>(new JwtAuthFilter(jwtService, tenants));
        reg.addUrlPatterns("/*");
        reg.setOrder(10); // 在 CorsFilter 之后,Controller 之前
        return reg;
    }
}
