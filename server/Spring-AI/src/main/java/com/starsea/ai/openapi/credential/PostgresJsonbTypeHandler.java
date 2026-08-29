package com.starsea.ai.openapi.credential;

import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.postgresql.util.PGobject;

import java.lang.reflect.Field;
import java.sql.PreparedStatement;
import java.sql.SQLException;

public class PostgresJsonbTypeHandler extends JacksonTypeHandler {

    public PostgresJsonbTypeHandler(Class<?> type) {
        super(type);
    }

    public PostgresJsonbTypeHandler(Class<?> type, Field field) {
        super(type, field);
    }

    @Override
    public void setNonNullParameter(PreparedStatement statement, int index, Object parameter, JdbcType jdbcType)
            throws SQLException {
        PGobject jsonb = new PGobject();
        jsonb.setType("jsonb");
        jsonb.setValue(toJson(parameter));
        statement.setObject(index, jsonb);
    }
}
