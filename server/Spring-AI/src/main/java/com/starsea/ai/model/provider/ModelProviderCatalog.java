package com.starsea.ai.model.provider;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.starsea.ai.openapi.credential.PostgresJsonbTypeHandler;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;

@Data
@TableName(value = "model_provider_catalog", autoResultMap = true)
public class ModelProviderCatalog {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String code;
    private String name;
    private String icon;
    private String defaultBaseUrl;
    private String protocolType;
    private String authType;
    @TableField(value = "suggested_models", typeHandler = PostgresJsonbTypeHandler.class)
    private List<ModelSuggestion> suggestedModels;
    private OffsetDateTime createTime;
    private OffsetDateTime updateTime;
}
