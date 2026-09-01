package com.starsea.ai.domain.vo;

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
    private Date createTime;
    private Date updateTime;
}
