package com.starsea.ai.controller;

import com.starsea.ai.agent.AgentWorkbenchException;
import com.starsea.ai.auth.RequireLogin;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Retired API: cannot bypass aggregate draft revisions, publication or encrypted provider connections. */
@RestController
@RequestMapping("/agent")
@RequireLogin
public class AgentController {
    @RequestMapping({"", "/**"})
    public void retired() {
        throw new AgentWorkbenchException(410, "AGENT_LEGACY_API_RETIRED", "旧智能体接口已停用，请使用新版智能体工作台");
    }
}
