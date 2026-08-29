package com.starsea.ai.controller;

import com.starsea.ai.auth.RequireLogin;
import com.starsea.ai.domain.vo.MessageVo;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @Projectname: Spring-AI
 * @Filename: AiHistoryController
 * @Author: FANSEA
 * @Date:2025/4/4 23:11
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/ai/history")
@RequireLogin
public class AiHistoryController {

    private final ChatMemory chatMemory;

    /*@GetMapping("/{type}")
    public List<String> getHistoryChatList(@PathVariable String type){
        return repositoryHistory.get(type);
    }*/

    @GetMapping("/{chatId}")
    public List<MessageVo> getHistoryChat(@PathVariable String chatId){
        List<Message> messages = chatMemory.get(chatId, Integer.MAX_VALUE);
        if (messages == null){
            return List.of();
        }
        List<Message> arrayList = new ArrayList<>();
        // 对user的message进行处理，提取prompt内容
        for(Message m:messages){
            if (m.getMessageType() == MessageType.USER){
                Message finalM = m;
                m = new Message() {
                    @Override
                    public MessageType getMessageType() {
                        return MessageType.USER;
                    }
                    @Override
                    public String getText() {
                        return extractPrompt(finalM.getText());
                    }
                    @Override
                    public Map<String, Object> getMetadata() {
                        return null;
                    }
                };
            }
            arrayList.add(m);
        }
        return arrayList.stream().map(MessageVo::new).toList();
    }

    public static String extractPrompt(String inputText) {
        // 正则匹配 "prompt":"(.*?)"
        Pattern pattern = Pattern.compile("\"prompt\"\\s*:\\s*\"(.*?)\"");
        Matcher matcher = pattern.matcher(inputText);
        if (matcher.find()) {
            return matcher.group(1); // 返回第一个捕获组（即 prompt 的值）
        }
        return null;
    }

}
