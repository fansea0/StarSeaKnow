package com.starsea.ai.agent.execution;

import com.starsea.ai.agent.AgentWorkbenchException;
import com.starsea.ai.auth.AuthContext;
import java.util.Objects;

final class AgentExecutionAccess {
    private AgentExecutionAccess() { }
    static AuthContext require(boolean draft) {
        AuthContext context = AuthContext.current();
        if (context == null || context.getKind() != AuthContext.Kind.BUSINESS || context.getTenantId() == null
                || !("tenant_admin".equals(context.getRole()) || "tenant_member".equals(context.getRole()))) {
            throw new AgentWorkbenchException(403, "AGENT_ACCESS_DENIED", "无权调用智能体");
        }
        if (draft && !"tenant_admin".equals(context.getRole())) throw new AgentWorkbenchException(
                403, "AGENT_ADMIN_REQUIRED", "需要租户管理员权限");
        return context;
    }
    static AuthContext require(ExecutionSource source) {
        AuthContext context = require(source.mode() == ExecutionSource.Mode.DRAFT);
        if (!Objects.equals(context.getTenantId(), source.tenantId())) throw new AgentWorkbenchException(
                404, "AGENT_NOT_FOUND", "智能体不存在或无权访问");
        return context;
    }
}
