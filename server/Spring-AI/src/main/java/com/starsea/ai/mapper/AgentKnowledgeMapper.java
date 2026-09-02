package com.starsea.ai.mapper;


import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.starsea.ai.domain.AgentKnowledge;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
* @author ASUS
* @description 针对表【agent_knowledge】的数据库操作Mapper
* @createDate 2025-05-03 11:33:17
* @Entity generator.domain.AgentKnowledge
*/
@Mapper
public interface AgentKnowledgeMapper extends BaseMapper<AgentKnowledge> {

    @Select("SELECT knowledge_id FROM agent_knowledge WHERE agent_id = #{agentId} AND tenant_id = #{tenantId} ORDER BY knowledge_id")
    List<Long> selectKnowledgeIds(@Param("agentId") long agentId, @Param("tenantId") long tenantId);

    @Delete("DELETE FROM agent_knowledge WHERE agent_id = #{agentId} AND tenant_id = #{tenantId}")
    int deleteByAgentId(@Param("agentId") long agentId, @Param("tenantId") long tenantId);

    @Insert("INSERT INTO agent_knowledge(agent_id, knowledge_id, tenant_id) VALUES(#{agentId}, #{knowledgeId}, #{tenantId})")
    int insertLink(@Param("agentId") long agentId, @Param("knowledgeId") long knowledgeId,
                   @Param("tenantId") long tenantId);
}



