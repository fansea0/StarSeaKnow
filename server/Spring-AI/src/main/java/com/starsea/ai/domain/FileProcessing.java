package com.starsea.ai.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.starsea.ai.openapi.credential.PostgresJsonbTypeHandler;
import lombok.Data;

import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.Map;

@Data
@TableName(value = "file_processing", autoResultMap = true)
public class FileProcessing implements Serializable {

    @TableId(value = "file_id", type = IdType.INPUT)
    private Long fileId;

    private Long tenantId;
    private Long knowledgeId;
    private Integer pipelineState;
    private Integer failedFromState;
    private Integer progress;
    private String sourceHash;
    private String strategyCode;
    private String plannerVersion;

    @TableField(value = "policy_snapshot", typeHandler = PostgresJsonbTypeHandler.class)
    private Map<String, Object> policySnapshot;

    @TableField(value = "context_policy", typeHandler = PostgresJsonbTypeHandler.class)
    private Map<String, Object> contextPolicy;

    @TableField(value = "execution_metadata", typeHandler = PostgresJsonbTypeHandler.class)
    private Map<String, Object> executionMetadata;

    @TableField(value = "preview_summary", typeHandler = PostgresJsonbTypeHandler.class)
    private Map<String, Object> previewSummary;

    private String lastError;
    private Integer lockVersion;
    private OffsetDateTime createTime;
    private OffsetDateTime updateTime;

    @TableField(exist = false)
    private static final long serialVersionUID = 1L;
}
