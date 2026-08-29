package com.starsea.ai.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@TableName("platform_invitation")
public class PlatformInvitation {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String code;
    private String status;
    private OffsetDateTime validFrom;
    private OffsetDateTime validUntil;
    private OffsetDateTime usedAt;
    private Long usedTenantId;
    private Long usedUserId;
    private Long createdBy;
    private OffsetDateTime createTime;
    private OffsetDateTime updateTime;
}
