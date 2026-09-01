package com.starsea.ai.service;

import com.starsea.ai.openapi.retrieval.RetrievalQuery;
import com.starsea.ai.openapi.retrieval.RetrievedChunk;

import java.util.List;

/**
 * @Projectname: Spring-AI
 * @Filename: FileService
 * @Author: FANSEA
 * @Date:2025/4/26 11:20
 */
public interface RagService {

    List<RetrievedChunk> retrieve(RetrievalQuery query);
}
