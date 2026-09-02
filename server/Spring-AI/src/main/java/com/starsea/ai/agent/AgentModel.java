package com.starsea.ai.agent;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Data
@TableName("agent_model")
public class AgentModel {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long tenantModelProviderId;
    private String modelId;
    private BigDecimal temperature;
    private BigDecimal topP;
    private Integer maxTokens;
    private Integer timeoutSeconds;
    private OffsetDateTime deletedAt;
    private Long deletedBy;
    private OffsetDateTime createTime;
    private OffsetDateTime updateTime;
}
