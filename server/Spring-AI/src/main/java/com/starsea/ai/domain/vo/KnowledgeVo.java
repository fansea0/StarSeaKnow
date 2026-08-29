package com.starsea.ai.domain.vo;

import lombok.Data;

/**
 * @Projectname: Spring-AI
 * @Filename: KnowledgeVo
 * @Author: FANSEA
 * @Date:2025/5/4 14:42
 */
@Data
public class KnowledgeVo {
    private Long id;
    private String name;
    private String description;
    private Long fileCount;
    private Long agentCount;
}
