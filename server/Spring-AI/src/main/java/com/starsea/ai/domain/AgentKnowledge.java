package com.fansea.ai.domain;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 
 * @TableName agent_knowledge
 */
@TableName(value ="agent_knowledge")
@Data
public class AgentKnowledge implements Serializable {

    private Long agentId;
    private Long knowledgeId;
    private Date createTime;

    public AgentKnowledge(Long agentId, Long knowledgeId, Date createTime) {
        this.agentId = agentId;
        this.knowledgeId = knowledgeId;
        this.createTime = createTime;
    }

    public AgentKnowledge(Long agentId, Long knowledgeId) {
        this.agentId = agentId;
        this.knowledgeId = knowledgeId;
    }

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
        AgentKnowledge other = (AgentKnowledge) that;
        return (this.getAgentId() == null ? other.getAgentId() == null : this.getAgentId().equals(other.getAgentId()))
            && (this.getKnowledgeId() == null ? other.getKnowledgeId() == null : this.getKnowledgeId().equals(other.getKnowledgeId()))
            && (this.getCreateTime() == null ? other.getCreateTime() == null : this.getCreateTime().equals(other.getCreateTime()));
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((getAgentId() == null) ? 0 : getAgentId().hashCode());
        result = prime * result + ((getKnowledgeId() == null) ? 0 : getKnowledgeId().hashCode());
        result = prime * result + ((getCreateTime() == null) ? 0 : getCreateTime().hashCode());
        return result;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(getClass().getSimpleName());
        sb.append(" [");
        sb.append("Hash = ").append(hashCode());
        sb.append(", agentId=").append(agentId);
        sb.append(", knowledgeId=").append(knowledgeId);
        sb.append(", createTime=").append(createTime);
        sb.append(", serialVersionUID=").append(serialVersionUID);
        sb.append("]");
        return sb.toString();
    }
}