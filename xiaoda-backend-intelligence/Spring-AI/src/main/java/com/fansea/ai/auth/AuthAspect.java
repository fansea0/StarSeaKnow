package com.fansea.ai.auth;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

@Aspect
@Component
public class AuthAspect {

    @Around("@annotation(com.fansea.ai.auth.RequireRole) || @within(com.fansea.ai.auth.RequireRole)")
    public Object requireRole(ProceedingJoinPoint pjp) throws Throwable {
        checkAuth();
        AuthContext ctx = AuthContext.current();
        RequireRole ann = findAnnotation(pjp);
        if (!ann.value().equals(ctx.getRole())) {
            throw new AuthException(AuthErrorCode.FORBIDDEN_ROLE, "role required: " + ann.value());
        }
        return pjp.proceed();
    }

    @Around("@annotation(com.fansea.ai.auth.RequireLogin) || @within(com.fansea.ai.auth.RequireLogin)")
    public Object requireLogin(ProceedingJoinPoint pjp) throws Throwable {
        checkAuth();
        return pjp.proceed();
    }

    private void checkAuth() {
        if (AuthContext.current() == null) {
            throw new AuthException(AuthErrorCode.MISSING_TOKEN, "login required");
        }
    }

    private RequireRole findAnnotation(ProceedingJoinPoint pjp) {
        var sig = (org.aspectj.lang.reflect.MethodSignature) pjp.getSignature();
        var method = sig.getMethod();
        // 方法级优先
        RequireRole ann = method.getAnnotation(RequireRole.class);
        if (ann != null) return ann;
        // fallback: 类级
        ann = method.getDeclaringClass().getAnnotation(RequireRole.class);
        if (ann != null) return ann;
        // @within 触发了但都找不到 —— 不应该发生(pointcut 不会匹配),防御抛错
        throw new IllegalStateException(
            "AuthAspect.requireRole matched but no @RequireRole annotation found on method "
            + method.getDeclaringClass().getName() + "#" + method.getName());
    }
}
