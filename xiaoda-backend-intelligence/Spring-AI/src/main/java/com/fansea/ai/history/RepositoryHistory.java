package com.fansea.ai.history;

import java.util.List;

/**
 * @Projectname: Spring-AI
 * @Filename: InMemoryHistory
 * @Author: FANSEA
 * @Date:2025/4/4 22:09
 */
public interface RepositoryHistory {

    /**
     * 保存历史记录
     * @param type
     * @param chatId
     */
    void save(String type,String chatId);


    /**
     * 获取历史记录
     * @param type
     */
    List<String> get(String type);

}
