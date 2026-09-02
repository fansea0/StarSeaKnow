package com.starsea.ai.controller;

import com.starsea.ai.auth.RequireLogin;
import com.starsea.ai.agent.AgentWorkbenchException;
import com.starsea.ai.history.RepositoryHistory;
import com.starsea.ai.openapi.retrieval.RetrievalQuery;
import com.starsea.ai.openapi.retrieval.RetrievedChunk;
import com.starsea.ai.service.RagService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Set;
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
        List<RetrievedChunk> documents = ragService.retrieve(
                new RetrievalQuery(prompt, Set.of(knowledgeId), 1, 0.0));
        //提取文本内容
        String content = documents.stream()
                .map(RetrievedChunk::content)
                .collect(Collectors.joining("n"));
        return chatClient.prompt()
                .user(getChatPrompt2String(prompt, content))
                .advisors(a -> a.param(CHAT_MEMORY_CONVERSATION_ID_KEY,chatId))
                .stream()
                .content();
    }

    @RequestMapping("/agent/chat")
    public void agentChat() {
        throw new AgentWorkbenchException(410, "AGENT_LEGACY_API_RETIRED", "旧智能体聊天接口已停用，请使用发布快照调用接口");
    }

    private String getChatPrompt2String(String message, String context) {
        String promptText = """
           请仅用以下内容回答"%s":
           %s
           """;
        return String.format(promptText, message, context);
    }

}
