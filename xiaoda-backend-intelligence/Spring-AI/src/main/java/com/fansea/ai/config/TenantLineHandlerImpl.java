package com.fansea.ai.config;

import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.fansea.ai.auth.AuthContext;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class TenantLineHandlerImpl implements TenantLineHandler {

    private static final Set<String> GLOBAL_TABLES = Set.of(
            "tenant", "invite", "refresh_token", "platform_admin"
    );

    @Override
    public Expression getTenantId() {
        AuthContext ctx = AuthContext.current();
        if (ctx == null || ctx.getTenantId() == null) return null; // skip
        return new LongValue(ctx.getTenantId());
    }

    @Override
    public boolean ignoreTable(String tableName) {
        return GLOBAL_TABLES.contains(tableName.toLowerCase());
    }
}
