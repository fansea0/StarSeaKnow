package com.fangsa.ai.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@TableName("refresh_token")
public class RefreshToken {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
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
