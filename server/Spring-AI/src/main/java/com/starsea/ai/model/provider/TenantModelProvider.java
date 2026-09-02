package com.starsea.ai.model.provider;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.starsea.ai.openapi.credential.PostgresJsonbTypeHandler;
import lombok.Data;
import lombok.ToString;

import java.time.OffsetDateTime;
import java.util.List;

@Data
@ToString(exclude = {"apiKeyCiphertext", "apiKeyNonce"})
@TableName(value = "tenant_model_provider", autoResultMap = true)
public class TenantModelProvider {
    @TableId(type = IdType.INPUT)
    private Long id;
    private Long tenantId;
    private Long catalogProviderId;
    private String customName;
    private String customIcon;
    private String baseUrl;
    private String protocolType;
    private String authType;
    @TableField(value = "selectable_models", typeHandler = PostgresJsonbTypeHandler.class)
    private List<ModelSuggestion> selectableModels;
    private String apiKeyCiphertext;
    private String apiKeyNonce;
    private String apiKeyVersion;
    private String apiKeyLastFour;
    private OffsetDateTime lastVerifiedAt;
    private Long createdBy;
    private OffsetDateTime createTime;
    private OffsetDateTime updateTime;
}
