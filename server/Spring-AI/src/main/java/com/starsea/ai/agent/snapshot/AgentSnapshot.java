package com.starsea.ai.agent.snapshot;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.starsea.ai.openapi.credential.PostgresJsonbTypeHandler;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@TableName(value = "agent_snapshot", autoResultMap = true)
public class AgentSnapshot {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long agentId;
    private Long versionNumber;
    private String publishNote;
    @TableField(value = "snapshot_data", typeHandler = PostgresJsonbTypeHandler.class)
    private AgentSnapshotData snapshotData;
    private Long sourceRevision;
    private Long rollbackFromSnapshotId;
    private Long createdBy;
    private OffsetDateTime createTime;
    private OffsetDateTime deletedAt;
    private Long deletedBy;
}
