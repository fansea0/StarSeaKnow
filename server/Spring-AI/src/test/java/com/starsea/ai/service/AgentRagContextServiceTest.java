package com.starsea.ai.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.starsea.ai.domain.AgentKnowledge;
import com.starsea.ai.openapi.retrieval.RetrievalQuery;
import com.starsea.ai.openapi.retrieval.RetrievedChunk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentRagContextServiceTest {

    private AgentKnowledgeService agentKnowledgeService;
    private RagService ragService;
    private AgentRagContextService service;

    @BeforeEach
    void setUp() {
        agentKnowledgeService = mock(AgentKnowledgeService.class);
        ragService = mock(RagService.class);
        service = new AgentRagContextService(agentKnowledgeService, ragService);
    }

    @Test
    void retrievesOneGlobalTopFiveAcrossAllAgentKnowledgeBases() {
        when(agentKnowledgeService.list(org.mockito.ArgumentMatchers.<Wrapper<AgentKnowledge>>any())).thenReturn(List.of(
                new AgentKnowledge(9L, 12L),
                new AgentKnowledge(9L, 11L)));
        when(ragService.retrieve(org.mockito.ArgumentMatchers.any())).thenReturn(List.of(
                chunk("第一条资料", 0.91),
                chunk("第二条资料", 0.77)));

        String context = service.retrieveContext(9L, "退款需要什么材料？");

        assertThat(context).isEqualTo("第一条资料\n\n第二条资料");
        ArgumentCaptor<RetrievalQuery> query = ArgumentCaptor.forClass(RetrievalQuery.class);
        verify(ragService, times(1)).retrieve(query.capture());
        assertThat(query.getValue().query()).isEqualTo("退款需要什么材料？");
        assertThat(query.getValue().knowledgeIds()).containsExactlyInAnyOrder(11L, 12L);
        assertThat(query.getValue().topK()).isEqualTo(5);
        assertThat(query.getValue().scoreThreshold()).isZero();
    }

    @Test
    void skipsVectorRetrievalWhenAgentHasNoKnowledgeBases() {
        when(agentKnowledgeService.list(org.mockito.ArgumentMatchers.<Wrapper<AgentKnowledge>>any())).thenReturn(List.of());

        String context = service.retrieveContext(9L, "你好");

        assertThat(context).isEmpty();
        verify(ragService, never()).retrieve(org.mockito.ArgumentMatchers.any());
    }

    private static RetrievedChunk chunk(String content, double score) {
        return new RetrievedChunk(content, score, "资料.pdf", UUID.randomUUID(), UUID.randomUUID(),
                "pdf", 1, 0);
    }
}
