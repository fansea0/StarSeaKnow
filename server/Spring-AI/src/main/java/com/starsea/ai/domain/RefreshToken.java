package com.starsea.ai.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.starsea.ai.auth.UuidTypeHandler;
import lombok.Data;
import org.apache.ibatis.type.JdbcType;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@TableName(value = "refresh_token", autoResultMap = true)
public class RefreshToken {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    @TableField(value = "family_id", typeHandler = UuidTypeHandler.class, jdbcType = JdbcType.OTHER)
    private UUID familyId;
    private String tokenHash;
    private Long parentId;
    private OffsetDateTime rotatedAt;
    private OffsetDateTime revokedAt;
    private OffsetDateTime expiresAt;
    private String userAgent;
    private String ip;
    private OffsetDateTime createTime;
}
