package com.fansea.ai.openapi.credential;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fansea.ai.auth.UuidTypeHandler;
import lombok.Data;
import org.apache.ibatis.type.JdbcType;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Data
@TableName(value = "api_credential", autoResultMap = true)
public class ApiCredential {
    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField(value = "public_id", typeHandler = UuidTypeHandler.class, jdbcType = JdbcType.OTHER)
    private UUID publicId;

    private Long tenantId;
    private String credentialType;
    private String name;
    private String description;
    private String keyId;
    private String secretDigest;
    private String pepperVersion;
    private String environment;
    private String status;
    private OffsetDateTime expiresAt;

    @TableField(value = "allowed_ip_cidrs", typeHandler = PostgresJsonbTypeHandler.class)
    private List<String> allowedIpCidrs;

    private Integer requestsPerMinute;
    private Integer burstCapacity;
    private Integer maxConcurrency;
    private Long authorizationVersion;
    private String displayPrefix;
    private String displayLastFour;
    private Long rotatedFromId;
    private Long createdBy;
    private OffsetDateTime createdAt;
    private OffsetDateTime revokedAt;
    private OffsetDateTime lastUsedAt;
    private String lastUsedIp;
}
