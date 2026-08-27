package com.fangsa.ai.auth;

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
    public FilterRegistrationBean<JwtAuthFilter> jwtAuthFilterRegistration(JwtService jwtService) {
        FilterRegistrationBean<JwtAuthFilter> reg = new FilterRegistrationBean<>(new JwtAuthFilter(jwtService));
        reg.addUrlPatterns("/*");
        reg.setOrder(10); // 在 CorsFilter 之后,Controller 之前
        return reg;
    }
}