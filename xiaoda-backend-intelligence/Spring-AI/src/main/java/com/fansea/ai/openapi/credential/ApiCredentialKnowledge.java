package com.fansea.ai.openapi.credential;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@TableName("api_credential_knowledge")
public class ApiCredentialKnowledge {
    private Long tenantId;
    private Long credentialId;
    private String credentialType;
    private Long knowledgeId;
    private OffsetDateTime createdAt;
}
