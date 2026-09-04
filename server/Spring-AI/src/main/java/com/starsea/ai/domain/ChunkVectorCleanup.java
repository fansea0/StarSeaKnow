package com.starsea.ai.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.starsea.ai.auth.UuidTypeHandler;
import lombok.Data;
import org.apache.ibatis.type.JdbcType;

import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@TableName(value = "chunk_vector_cleanup", autoResultMap = true)
public class ChunkVectorCleanup implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;
    @TableField(value = "vector_id", typeHandler = UuidTypeHandler.class,
            jdbcType = JdbcType.OTHER)
    private UUID vectorId;
    private Long tenantId;
    private Long knowledgeId;
    private Long fileId;
    @TableField(value = "chunk_public_id", typeHandler = UuidTypeHandler.class,
            jdbcType = JdbcType.OTHER)
    private UUID chunkPublicId;
    private Integer state;
    private Integer retryCount;
    private String lastError;
    private OffsetDateTime createTime;
    private OffsetDateTime updateTime;
}
