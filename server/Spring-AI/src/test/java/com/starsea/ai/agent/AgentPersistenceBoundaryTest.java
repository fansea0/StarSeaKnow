package com.starsea.ai.agent;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.starsea.ai.domain.Agent;
import com.starsea.ai.mapper.AgentMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.scripting.xmltags.XMLLanguageDriver;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class AgentPersistenceBoundaryTest {
    @Test void clearing_optional_fields_generates_actual_null_assignments_in_update_sql() {
        var configuration = new MybatisConfiguration();
        var assistant = new MapperBuilderAssistant(configuration, "agent-update-contract");
        assistant.setCurrentNamespace(AgentMapper.class.getName());
        var mapping = TableInfoHelper.initTableInfo(assistant, Agent.class);
        Agent row = new Agent(); row.setId(101L); row.setName("unchanged");
        var sql = new XMLLanguageDriver().createSqlSource(configuration,
                "<script>UPDATE agent <set>" + mapping.getAllSqlSet(false, "et.") + "</set> WHERE id=#{et.id}</script>", Map.class)
                .getBoundSql(Map.of("et", row)).getSql().replaceAll("\\s+", "");
        assertThat(sql).contains("agent_model_id=?", "description=?", "prologue=?");
    }

    @Test void agent_with_json_variables_does_not_fail_at_mapper_cache_boundary() {
        var configuration = new MybatisConfiguration();
        configuration.addMapper(AgentMapper.class);
        Agent row = new Agent(); row.setId(101L);
        row.setVariables(List.of(new AgentWorkbenchApiModels.VariableDefinition("company", "公司", "星海", true)));
        assertThatCode(() -> {
            for (var cache : configuration.getCaches()) {
                if (cache.getId().equals(AgentMapper.class.getName())) {
                    cache.putObject("agent-with-variable", List.of(row));
                    cache.getObject("agent-with-variable");
                }
            }
        }).doesNotThrowAnyException();
    }
}
