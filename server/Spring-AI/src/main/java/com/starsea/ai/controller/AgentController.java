package com.starsea.ai.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.starsea.ai.auth.RequireLogin;
import com.starsea.ai.auth.RequireRole;
import com.starsea.ai.domain.Agent;
import com.starsea.ai.domain.AgentKnowledge;
import com.starsea.ai.domain.Knowledge;
import com.starsea.ai.domain.dto.AjaxResult;
import com.starsea.ai.service.AgentKnowledgeService;
import com.starsea.ai.service.AgentService;
import com.starsea.ai.service.KnowledgeService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Objects;

/**
 * @Projectname: Spring-AI
 * @Filename: AgentController
 * @Author: FANSEA
 * @Date:2025/5/3 20:03
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/agent")
@RequireLogin
public class AgentController {

    private final AgentService agentService;
    private final AgentKnowledgeService agentKnowledgeService;
    private final KnowledgeService knowledgeService;

    @RequireRole("tenant_admin")
    @GetMapping("/agentToKnowledge")
    public AjaxResult agentToKnowledge(Long agentId,Long knowledgeId) {
        agentKnowledgeService.save(new AgentKnowledge(agentId,knowledgeId));
        return AjaxResult.success();
    }
    
    @GetMapping("/knowledge/list")
    public AjaxResult knowledgeList(Long agentId) {
        List<AgentKnowledge> list = agentKnowledgeService.list(new LambdaQueryWrapper<AgentKnowledge>().eq(AgentKnowledge::getAgentId, agentId));
        List<Knowledge> knowledges = list.stream().map(ak -> knowledgeService.getById(ak.getKnowledgeId())).toList();
        return AjaxResult.success(knowledges);
    }

    // 新增 Agent
    @RequireRole("tenant_admin")
    @PostMapping("/add")
    public AjaxResult addAgent(@RequestBody Agent agent) {
        agentService.save(agent);
        return AjaxResult.success();
    }

    // 更新 Agent
    @RequireRole("tenant_admin")
    @PutMapping("/update/{id}")
    public AjaxResult updateAgent(@PathVariable Long id, @RequestBody Agent agent) {
        Agent existing = agentService.getById(id);
        if (existing == null) {
            return AjaxResult.error("智能体不存在");
        }
        if (agent.getModelApiKey() == null || agent.getModelApiKey().isBlank()) {
            agent.setModelApiKey(existing.getModelApiKey());
        }
        agent.setId(id);
        agentService.updateById(agent);
        return AjaxResult.success();
    }

    // 删除 Agent
    @RequireRole("tenant_admin")
    @DeleteMapping("/delete/{id}")
    public AjaxResult deleteAgent(@PathVariable Long id) {
        agentService.removeById(id);
        return AjaxResult.success();
    }

    // 删除 Agent
    @RequireRole("tenant_admin")
    @DeleteMapping("/delete/knowledge/{knowledgeId}")
    public AjaxResult deleteAgentAndKnowledge(Long agentId, @PathVariable Long knowledgeId) {
        agentKnowledgeService.remove(new LambdaQueryWrapper<AgentKnowledge>()
                .eq(AgentKnowledge::getAgentId, agentId)
                .eq(AgentKnowledge::getKnowledgeId, knowledgeId));
        return AjaxResult.success();
    }

    // 查询特定 Agent
    @GetMapping("/{id}")
    public AjaxResult getAgentById(@PathVariable Long id) {
        Agent agent = agentService.getById(id);
        markApiKeyConfigured(agent);
        return AjaxResult.success(agent);
    }

    // 查询所有 Agent
    @GetMapping("/list")
    public AjaxResult getAllAgents() {
        List<Agent> agents = agentService.list();
        agents.forEach(this::markApiKeyConfigured);
        return AjaxResult.success(agents);
    }

    private void markApiKeyConfigured(Agent agent) {
        if (agent != null) {
            agent.setModelApiKeyConfigured(Objects.nonNull(agent.getModelApiKey()) && !agent.getModelApiKey().isBlank());
        }
    }

}
