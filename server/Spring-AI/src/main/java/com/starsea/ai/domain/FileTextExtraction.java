package com.starsea.ai.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.OffsetDateTime;

@Data
@TableName("file_text_extraction")
public class FileTextExtraction implements Serializable {

    @TableId(value = "file_id", type = IdType.INPUT)
    private Long fileId;
    private Long tenantId;
    private String sourceHash;
    private String extractorId;
    private String extractorVersion;
    private String mediaType;
    private String managedTextPath;
    private String sourceMapPath;
    private Long characterCount;
    private OffsetDateTime createTime;
    private OffsetDateTime updateTime;
}
