package com.fangsa.ai.auth;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

import java.sql.*;
import java.util.UUID;

public class UuidTypeHandler extends BaseTypeHandler<UUID> {
    @Override public void setNonNullParameter(PreparedStatement ps, int i, UUID p, JdbcType jdbcType) throws SQLException {
        ps.setObject(i, p);
    }
    @Override public UUID getNullableResult(ResultSet rs, String col) throws SQLException {
        Object o = rs.getObject(col); return o == null ? null : UUID.fromString(o.toString());
    }
    @Override public UUID getNullableResult(ResultSet rs, int col) throws SQLException {
        Object o = rs.getObject(col); return o == null ? null : UUID.fromString(o.toString());
    }
    @Override public UUID getNullableResult(CallableStatement cs, int col) throws SQLException {
        Object o = cs.getObject(col); return o == null ? null : UUID.fromString(o.toString());
    }
}
