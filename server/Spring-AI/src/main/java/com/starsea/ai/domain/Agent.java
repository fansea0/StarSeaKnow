package com.starsea.ai.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.starsea.ai.agent.AgentWorkbenchApiModels;
import com.starsea.ai.openapi.credential.PostgresJsonbTypeHandler;
import lombok.Data;
import lombok.ToString;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

@Data
@ToString(exclude = "modelApiKey")
@TableName(value = "agent", autoResultMap = true)
public class Agent implements Serializable {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private String name;
    private String description;
    private String prologue;

    /** Legacy fields retained only during the V12-to-V14 migration window. */
    private String roleDescription;
    private String modelUrl;
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String modelApiKey;
    private String modelId;
    @TableField(exist = false)
    private boolean modelApiKeyConfigured;

    private String systemPrompt;
    @TableField(value = "tags", typeHandler = PostgresJsonbTypeHandler.class)
    private List<String> tags;
    @TableField(value = "variables", typeHandler = PostgresJsonbTypeHandler.class)
    private List<AgentWorkbenchApiModels.VariableDefinition> variables;
    private Long agentModelId;
    private Integer retrievalTopK;
    private BigDecimal retrievalScoreThreshold;
    private Long draftRevision;
    private Long publishedRevision;
    private Long currentSnapshotId;
    private Long lockVersion;
    private OffsetDateTime lastDebuggedAt;
    private Long lastDebuggedBy;
    private Long lastEditedBy;
    private OffsetDateTime deletedAt;
    private Long deletedBy;
    private OffsetDateTime createTime;
    private OffsetDateTime updateTime;

    @TableField(exist = false)
    private static final long serialVersionUID = 1L;
}
