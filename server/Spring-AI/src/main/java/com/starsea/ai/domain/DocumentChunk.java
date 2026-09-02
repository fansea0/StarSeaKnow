package com.starsea.ai.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.starsea.ai.auth.UuidTypeHandler;
import com.starsea.ai.openapi.credential.PostgresJsonbTypeHandler;
import lombok.Data;
import org.apache.ibatis.type.JdbcType;

import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Data
@TableName(value = "document_chunk", autoResultMap = true)
public class DocumentChunk implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField(value = "public_id", typeHandler = UuidTypeHandler.class, jdbcType = JdbcType.OTHER)
    private UUID publicId;

    private Long tenantId;
    private Long knowledgeId;
    private Long fileId;
    private Integer position;
    private String content;
    private Boolean overlapEnabled;
    private Integer overlapTokenLimit;
    private String overlapContent;
    private Long overlapSourceChunkId;
    private Integer overlapTokenCount;
    private String indexContent;

    @TableField(value = "section_path", typeHandler = PostgresJsonbTypeHandler.class)
    private List<String> sectionPath;

    @TableField(value = "source_locator", typeHandler = PostgresJsonbTypeHandler.class)
    private Map<String, Object> sourceLocator;

    private Integer tokenCount;
    private String contentHash;

    @TableField(value = "boundary_reason", typeHandler = PostgresJsonbTypeHandler.class)
    private Map<String, Object> boundaryReason;

    private Integer status;

    @TableField("is_modified")
    private Boolean isModified;
    private String lastError;
    private Integer lockVersion;
    private OffsetDateTime createTime;
    private OffsetDateTime updateTime;

    @TableField(exist = false)
    private UUID sourceDocumentPublicId;

    @TableField(exist = false)
    private String sourceFileName;

    @TableField(exist = false)
    private String sourceFileType;

    @TableField(exist = false)
    private static final long serialVersionUID = 1L;
}
