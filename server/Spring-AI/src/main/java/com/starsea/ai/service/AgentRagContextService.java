package com.starsea.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.starsea.ai.domain.AgentKnowledge;
import com.starsea.ai.openapi.retrieval.RetrievalQuery;
import com.starsea.ai.openapi.retrieval.RetrievedChunk;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AgentRagContextService {

    private static final int DEFAULT_TOP_K = 5;
    private static final double DEFAULT_SCORE_THRESHOLD = 0.0;

    private final AgentKnowledgeService agentKnowledgeService;
    private final RagService ragService;

    public String retrieveContext(Long agentId, String prompt) {
        Set<Long> knowledgeIds = agentKnowledgeService.list(
                        new LambdaQueryWrapper<AgentKnowledge>().eq(AgentKnowledge::getAgentId, agentId))
                .stream()
                .map(AgentKnowledge::getKnowledgeId)
                .filter(Objects::nonNull)
                .collect(Collectors.toUnmodifiableSet());
        if (knowledgeIds.isEmpty()) {
            return "";
        }

        return ragService.retrieve(new RetrievalQuery(prompt, knowledgeIds, DEFAULT_TOP_K,
                        DEFAULT_SCORE_THRESHOLD))
                .stream()
                .map(RetrievedChunk::content)
                .filter(Objects::nonNull)
                .collect(Collectors.joining("\n\n"));
    }
}
