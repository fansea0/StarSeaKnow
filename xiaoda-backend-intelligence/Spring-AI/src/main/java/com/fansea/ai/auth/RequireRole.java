package com.fansea.ai.auth;

import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface RequireRole {
    String value(); // "tenant_admin" | "platform_admin"
}
