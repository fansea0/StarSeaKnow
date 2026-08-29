package com.fansea.ai.openapi.auth;

import com.fansea.ai.openapi.credential.ApiCredentialResolver;
import com.fansea.ai.openapi.credential.ApiKeyCodec;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerExceptionResolver;

@Configuration
public class ExternalApiFilterConfig {

    @Bean
    public FilterRegistrationBean<ExternalApiKeyFilter> externalApiKeyFilterRegistration(
            ApiKeyCodec codec,
            ApiCredentialResolver resolver,
            ClientIpResolver clientIpResolver,
            InMemoryAuthenticationAttemptLimiter authenticationAttempts,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptionResolver) {
        FilterRegistrationBean<ExternalApiKeyFilter> registration = new FilterRegistrationBean<>(
                new ExternalApiKeyFilter(codec, resolver, clientIpResolver, authenticationAttempts, exceptionResolver));
        registration.addUrlPatterns("/*");
        registration.setOrder(9);
        return registration;
    }
}
