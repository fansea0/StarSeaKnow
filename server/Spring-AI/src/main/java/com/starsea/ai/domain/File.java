package com.starsea.ai.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import org.apache.ibatis.type.ObjectTypeHandler;
import java.io.Serializable;
import java.util.Date;
import java.util.UUID;

/**
 * 
 * @TableName file
 */
@TableName(value ="file", autoResultMap = true)
@Data
public class File implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;
    @TableField(value = "public_id", typeHandler = ObjectTypeHandler.class)
    private UUID publicId;
    @TableField("file_name")
    private String fileName;
    @TableField("size")
    private Long size;
    @TableField("status")
    private Integer status;
    @TableField("type")
    private String type;
    @TableField("path")
    private String path;
    @TableField("create_time")
    private Date createTime;
    @TableField("update_time")
    private Date updateTime;

    @TableField(exist = false)
    private static final long serialVersionUID = 1L;

    public File() {
    }

    public File(Long id, String fileName, Long size, Integer status, String type, String path, Date createTime, Date updateTime) {
        this.id = id;
        this.fileName = fileName;
        this.size = size;
        this.status = status;
        this.type = type;
        this.path = path;
        this.createTime = createTime;
        this.updateTime = updateTime;
    }

    public File(String fileName, Long size, String type, String path) {
        this.fileName = fileName;
        this.size = size;
        this.type = type;
        this.path = path;
    }
}
