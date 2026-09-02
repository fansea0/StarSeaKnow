package com.starsea.ai.mapper;


import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.starsea.ai.domain.Agent;
import org.apache.ibatis.annotations.CacheNamespace;
import org.apache.ibatis.annotations.Mapper;

/**
* @author ASUS
* @description 针对表【agent】的数据库操作Mapper
* @createDate 2025-05-03 11:33:17
* @Entity generator.domain.Agent
*/
@Mapper
@CacheNamespace
public interface AgentMapper extends BaseMapper<Agent> {

    @org.apache.ibatis.annotations.Update("""
            UPDATE agent SET last_debugged_at = #{at}, last_debugged_by = #{userId}
            WHERE id = #{id} AND tenant_id = #{tenantId} AND deleted_at IS NULL
            """)
    int markDebugged(@org.apache.ibatis.annotations.Param("id") long id,
                     @org.apache.ibatis.annotations.Param("tenantId") long tenantId,
                     @org.apache.ibatis.annotations.Param("userId") long userId,
                     @org.apache.ibatis.annotations.Param("at") java.time.OffsetDateTime at);

    default Agent selectForUpdate(long id, long tenantId) {
        return selectOne(new LambdaQueryWrapper<Agent>()
                .eq(Agent::getId, id)
                .eq(Agent::getTenantId, tenantId)
                .isNull(Agent::getDeletedAt)
                .last("FOR UPDATE"));
    }
}


