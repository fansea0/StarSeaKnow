package com.starsea.ai.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 
 * @TableName agent
 */
@TableName(value ="agent")
@Data
public class Agent implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private String description;
    private String prologue;
    private String roleDescription;
    private String modelUrl;
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String modelApiKey;
    private String modelId;
    @TableField(exist = false)
    private boolean modelApiKeyConfigured;
    private Date createTime;
    private Date updateTime;
    @TableField(exist = false)
    private static final long serialVersionUID = 1L;

    @Override
    public boolean equals(Object that) {
        if (this == that) {
            return true;
        }
        if (that == null) {
            return false;
        }
        if (getClass() != that.getClass()) {
            return false;
        }
        Agent other = (Agent) that;
        return (this.getId() == null ? other.getId() == null : this.getId().equals(other.getId()))
            && (this.getName() == null ? other.getName() == null : this.getName().equals(other.getName()))
            && (this.getDescription() == null ? other.getDescription() == null : this.getDescription().equals(other.getDescription()))
            && (this.getPrologue() == null ? other.getPrologue() == null : this.getPrologue().equals(other.getPrologue()))
            && (this.getRoleDescription() == null ? other.getRoleDescription() == null : this.getRoleDescription().equals(other.getRoleDescription()))
            && (this.getModelUrl() == null ? other.getModelUrl() == null : this.getModelUrl().equals(other.getModelUrl()))
            && (this.getModelApiKey() == null ? other.getModelApiKey() == null : this.getModelApiKey().equals(other.getModelApiKey()))
            && (this.getModelId() == null ? other.getModelId() == null : this.getModelId().equals(other.getModelId()))
            && (this.getCreateTime() == null ? other.getCreateTime() == null : this.getCreateTime().equals(other.getCreateTime()))
            && (this.getUpdateTime() == null ? other.getUpdateTime() == null : this.getUpdateTime().equals(other.getUpdateTime()));
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((getId() == null) ? 0 : getId().hashCode());
        result = prime * result + ((getName() == null) ? 0 : getName().hashCode());
        result = prime * result + ((getDescription() == null) ? 0 : getDescription().hashCode());
        result = prime * result + ((getPrologue() == null) ? 0 : getPrologue().hashCode());
        result = prime * result + ((getRoleDescription() == null) ? 0 : getRoleDescription().hashCode());
        result = prime * result + ((getModelUrl() == null) ? 0 : getModelUrl().hashCode());
        result = prime * result + ((getModelApiKey() == null) ? 0 : getModelApiKey().hashCode());
        result = prime * result + ((getModelId() == null) ? 0 : getModelId().hashCode());
        result = prime * result + ((getCreateTime() == null) ? 0 : getCreateTime().hashCode());
        result = prime * result + ((getUpdateTime() == null) ? 0 : getUpdateTime().hashCode());
        return result;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(getClass().getSimpleName());
        sb.append(" [");
        sb.append("Hash = ").append(hashCode());
        sb.append(", id=").append(id);
        sb.append(", name=").append(name);
        sb.append(", description=").append(description);
        sb.append(", prologue=").append(prologue);
        sb.append(", roleDescription=").append(roleDescription);
        sb.append(", modelUrl=").append(modelUrl);
        sb.append(", modelApiKeyConfigured=").append(modelApiKey != null && !modelApiKey.isBlank());
        sb.append(", modelId=").append(modelId);
        sb.append(", createTime=").append(createTime);
        sb.append(", updateTime=").append(updateTime);
        sb.append(", serialVersionUID=").append(serialVersionUID);
        sb.append("]");
        return sb.toString();
    }
}
