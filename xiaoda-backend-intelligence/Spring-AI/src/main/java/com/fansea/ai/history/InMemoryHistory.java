package com.fansea.ai.history;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @Projectname: Spring-AI
 * @Filename: InMemoryHistory
 * @Author: FANSEA
 * @Date:2025/4/4 22:17
 */
@Component
public class InMemoryHistory implements RepositoryHistory {

    private final Map<String,List<String>> chatHistoryIdMap = new HashMap<>();

    @Override
    public void save(String type, String chatId) {
         // key的空处理
         List<String> chatHistory = chatHistoryIdMap.computeIfAbsent(type, k -> new ArrayList<>());
         if (chatHistory.contains(chatId)){
             return;
         }
         chatHistory.add(chatId);
    }

    @Override
    public List<String> get(String type) {
        // 值的空处理
        return chatHistoryIdMap.getOrDefault(type,List.of());
    }
}
