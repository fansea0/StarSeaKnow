package com.starsea.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.starsea.ai.agent.snapshot.AgentSnapshot;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface AgentSnapshotMapper extends BaseMapper<AgentSnapshot> {

    @Select("SELECT COALESCE(MAX(version_number), 0) + 1 FROM agent_snapshot WHERE agent_id = #{agentId} AND tenant_id = #{tenantId}")
    long nextVersion(@Param("agentId") long agentId, @Param("tenantId") long tenantId);

    @Select("""
            SELECT COUNT(*) FROM agent_snapshot s
            JOIN agent a ON a.current_snapshot_id = s.id AND a.tenant_id = s.tenant_id
            WHERE s.tenant_id = #{tenantId} AND s.deleted_at IS NULL AND a.deleted_at IS NULL
              AND s.snapshot_data #>> '{model,providerConnectionId}' = CAST(#{providerId} AS text)
            """)
    long countActiveProviderUsage(
            @Param("tenantId") long tenantId,
            @Param("providerId") long providerId);

    @Select("""
            <script>
            SELECT COUNT(*) FROM agent_snapshot s
            JOIN agent a ON a.current_snapshot_id = s.id AND a.tenant_id = s.tenant_id
            WHERE s.tenant_id = #{tenantId} AND s.deleted_at IS NULL AND a.deleted_at IS NULL
              AND s.snapshot_data #>> '{model,providerConnectionId}' = CAST(#{providerId} AS text)
              AND s.snapshot_data #>> '{model,modelId}' IN
              <foreach collection='modelIds' item='modelId' open='(' separator=',' close=')'>#{modelId}</foreach>
            </script>
            """)
    long countActiveModelUsage(
            @Param("tenantId") long tenantId,
            @Param("providerId") long providerId,
            @Param("modelIds") List<String> modelIds);
}
