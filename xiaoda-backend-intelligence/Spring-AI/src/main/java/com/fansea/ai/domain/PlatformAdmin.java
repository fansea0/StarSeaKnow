package com.fansea.ai.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@TableName("platform_admin")
public class PlatformAdmin {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String username;
    private String passwordHash;
    private Boolean mustChangePassword;
    private OffsetDateTime createTime;
}
