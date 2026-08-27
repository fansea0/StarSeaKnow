package com.fansea.ai.service;

import com.fansea.ai.domain.File;
import org.springframework.ai.document.Document;

import java.util.List;

/**
 * @Projectname: Spring-AI
 * @Filename: FileService
 * @Author: FANSEA
 * @Date:2025/4/26 11:20
 */
public interface RagService {

    // 向量化处理
    void vectorize(File file, Long knowledgeId);


    // 匹配相似文档内容
    List<Document> search(String keyword,String knowledgeId);

    // 匹配相似文档内容
    List<Document> searchByFile(String keyword,Long knowledgeId);
}
