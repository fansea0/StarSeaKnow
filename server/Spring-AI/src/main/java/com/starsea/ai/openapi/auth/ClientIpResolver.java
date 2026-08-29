package com.starsea.ai.openapi.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class ClientIpResolver {

    private final ExternalApiTransportProperties properties;

    public ClientIpResolver(ExternalApiTransportProperties properties) {
        this.properties = properties;
    }

    public String clientIp(HttpServletRequest request) {
        String remoteAddress = request.getRemoteAddr();
        if (!isTrustedProxy(remoteAddress)) {
            return remoteAddress;
        }
        String forwarded = firstForwardedValue(request.getHeader("X-Forwarded-For"));
        return IpCidrMatcher.isIpLiteral(forwarded) ? forwarded : remoteAddress;
    }

    public boolean isSecure(HttpServletRequest request) {
        if (!isTrustedProxy(request.getRemoteAddr())) {
            return request.isSecure();
        }
        String forwardedScheme = firstForwardedValue(request.getHeader("X-Forwarded-Proto"));
        return "https".equalsIgnoreCase(forwardedScheme) || request.isSecure();
    }

    public boolean requiresHttps() {
        return properties.isRequireHttps();
    }

    private boolean isTrustedProxy(String remoteAddress) {
        return properties.getTrustedProxies().stream()
                .anyMatch(cidr -> IpCidrMatcher.matches(remoteAddress, cidr));
    }

    private String firstForwardedValue(String header) {
        if (!StringUtils.hasText(header)) {
            return null;
        }
        int comma = header.indexOf(',');
        return (comma >= 0 ? header.substring(0, comma) : header).trim();
    }
}
