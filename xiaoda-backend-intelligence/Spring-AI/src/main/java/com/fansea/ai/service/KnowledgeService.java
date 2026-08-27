package com.fansea.ai.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.fansea.ai.domain.Knowledge;

/**
* @author ASUS
* @description 针对表【knowledge】的数据库操作Service
* @createDate 2025-05-03 11:33:17
*/
public interface KnowledgeService extends IService<Knowledge> {

    void loadEmbedding(Long knowledgeId,Long fileId);
}
