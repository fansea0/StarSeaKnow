package com.fansea.ai.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * @Projectname: Spring-AI
 * @Filename: WebMvcConfig
 * @Author: FANSEA
 * @Date:2025/4/3 21:56
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOrigins(List.of(
                        "http://localhost:5173",
                        "http://127.0.0.1:5173",
                        "http://localhost:5174",
                        "http://127.0.0.1:5174"
                ).toArray(new String[0]))
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders("Authorization")
                .allowCredentials(true)
                .maxAge(3600);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 注:JwtAuthFilter 通过 FilterRegistrationBean 注册(见 PasswordEncoderConfig),
        // 这里只注册 HandlerInterceptor
        registry.addInterceptor(new com.fansea.ai.auth.TenantContextInterceptor())
                .addPathPatterns("/**");
    }
}
