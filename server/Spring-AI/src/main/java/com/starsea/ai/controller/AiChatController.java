package com.starsea.ai.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.starsea.ai.auth.RequireLogin;
import com.starsea.ai.domain.Agent;
import com.starsea.ai.domain.AgentKnowledge;
import com.starsea.ai.history.RepositoryHistory;
import com.starsea.ai.model.AgentChatClientFactory;
import com.starsea.ai.service.AgentKnowledgeService;
import com.starsea.ai.service.AgentService;
import com.starsea.ai.service.RagService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

import static org.springframework.ai.chat.client.advisor.AbstractChatMemoryAdvisor.CHAT_MEMORY_CONVERSATION_ID_KEY;

/**
 * @Projectname: Spring-AI
 * @Filename: TestController
 * @Author: FANSEA
 * @Date:2025/4/3 17:57
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/ai")
@RequireLogin
public class AiChatController {

    private final RepositoryHistory repositoryHistory;
    private final ChatClient chatClient;
    private final RagService ragService;
    private final AgentService agentService;
    private final AgentKnowledgeService agentKnowledgeService;
    private final AgentChatClientFactory agentChatClientFactory;


    // 指定字符编码否则无法正确展示
    @RequestMapping(value = "/chat",produces = "text/html;charset=utf-8")
    public Flux<String> chat(String prompt,String chatId){
        repositoryHistory.save("chat",chatId);
        return chatClient.prompt()
                .user(prompt)
                .advisors(a -> a.param(CHAT_MEMORY_CONVERSATION_ID_KEY,chatId))
                .stream()
                .content();
    }

    // 指定字符编码否则无法正确展示
    @PostMapping(value = "/knowledge/chat",produces = "text/html;charset=utf-8")
    public Flux<String> knowledgeChat(@RequestBody String prompt, String chatId, Long knowledgeId){
        repositoryHistory.save("chat",chatId);
        List<Document> documents = ragService.searchByFile(prompt,knowledgeId);
        //提取文本内容
        String content = documents.stream()
                .map(Document::getText)
                .collect(Collectors.joining("n"));
        return chatClient.prompt()
                .user(getChatPrompt2String(prompt, content))
                .advisors(a -> a.param(CHAT_MEMORY_CONVERSATION_ID_KEY,chatId))
                .stream()
                .content();
    }

    // 指定智能体回复
    @PostMapping(value = "/agent/chat",produces = "text/html;charset=utf-8")
    public Flux<String> agentChat(@RequestBody String prompt, String chatId, Long agentId){
        Agent agent = agentService.getById(agentId);
        List<Long> knowledgeIds = agentKnowledgeService.list(new LambdaQueryWrapper<AgentKnowledge>().eq(AgentKnowledge::getAgentId, agentId))
                .stream().map(AgentKnowledge::getKnowledgeId).toList();
        List<Document> documents = knowledgeIds.stream()
                .map(id -> ragService.searchByFile(prompt, id))
                .flatMap(Collection::stream) // 将所有列表合并为一个流
                .toList();
        //提取文本内容
        String content = documents.stream()
                .map(Document::getText)
                .collect(Collectors.joining("n"));
        return agentChatClientFactory.create(agent).prompt()
                .system(agent.getRoleDescription())
                .user(getChatPrompt2String(prompt, content))
                .advisors(a -> a.param(CHAT_MEMORY_CONVERSATION_ID_KEY,chatId))
                .stream()
                .content();
    }

    private String getChatPrompt2String(String message, String context) {
        String promptText = """
           请仅用以下内容回答"%s":
           %s
           """;
        return String.format(promptText, message, context);
    }

}
