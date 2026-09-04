package com.starsea.ai.domain.vo;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.util.Date;

/**
 * @Projectname: Spring-AI
 * @Filename: FileVo
 * @Author: FANSEA
 * @Date:2025/5/4 15:37
 */
@Data
public class FileVo {
    private Long id;
    private String fileName;
    private Long size;
    private Integer status;
    private String type;
    @JsonIgnore
    private String path;
    private ChunkingCapability chunkingCapability;
    private Integer pipelineState;
    private Integer progress;
    private String processingError;
    private Date createTime;
    private Date updateTime;

    public record ChunkingCapability(boolean available, String reason) {
    }
}
