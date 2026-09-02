package com.starsea.ai.controller;

import com.starsea.ai.auth.AuthContext;
import com.starsea.ai.domain.Knowledge;
import com.starsea.ai.domain.dto.AjaxResult;
import com.starsea.ai.domain.vo.KnowledgeVo;
import com.starsea.ai.service.AgentKnowledgeService;
import com.starsea.ai.service.FileService;
import com.starsea.ai.service.KnowledgeFileService;
import com.starsea.ai.service.KnowledgeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringJUnitConfig(KnowledgeControllerTenantIsolationTest.TestConfig.class)
class KnowledgeControllerTenantIsolationTest {

    @Autowired
    private KnowledgeController controller;

    @Autowired
    private KnowledgeService knowledgeService;

    @Autowired
    private KnowledgeFileService knowledgeFileService;

    @Autowired
    private AgentKnowledgeService agentKnowledgeService;

    @Autowired
    private CacheManager cacheManager;

    @BeforeEach
    void setUp() {
        reset(knowledgeService, knowledgeFileService, agentKnowledgeService);
        cacheManager.getCache("knowledge").clear();
    }

    @AfterEach
    void tearDown() {
        AuthContext.clear();
    }

    @Test
    void listCacheDoesNotReturnAnotherTenantsKnowledge() {
        Knowledge tenantOneKnowledge = knowledge(11L, "租户一知识库");
        Knowledge tenantTwoKnowledge = knowledge(22L, "租户二知识库");
        when(knowledgeService.list()).thenAnswer(invocation ->
                AuthContext.current().getTenantId() == 1L
                        ? List.of(tenantOneKnowledge)
                        : List.of(tenantTwoKnowledge));

        AuthContext.set(businessContext(101L, 1L));
        AjaxResult tenantOneResult = controller.listAllKnowledgeVo();

        AuthContext.set(businessContext(202L, 2L));
        AjaxResult tenantTwoResult = controller.listAllKnowledgeVo();

        assertEquals("租户一知识库", firstKnowledgeName(tenantOneResult));
        assertEquals("租户二知识库", firstKnowledgeName(tenantTwoResult));
        verify(knowledgeService, org.mockito.Mockito.times(2)).list();
    }

    @Test
    void detailReturnsNotFoundWhenKnowledgeIsOutsideCurrentTenant() {
        AuthContext.set(businessContext(202L, 2L));
        when(knowledgeService.getById(11L)).thenReturn(null);

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> controller.getKnowledgeById(11L));

        assertEquals(404, exception.getStatusCode().value());
        assertEquals("知识库不存在或无权访问", exception.getReason());
    }

    private static AuthContext businessContext(long userId, long tenantId) {
        return new AuthContext(AuthContext.Kind.BUSINESS, userId, tenantId, "tenant_admin", "test-jti");
    }

    private static Knowledge knowledge(long id, String name) {
        Knowledge knowledge = new Knowledge();
        knowledge.setId(id);
        knowledge.setName(name);
        return knowledge;
    }

    @SuppressWarnings("unchecked")
    private static String firstKnowledgeName(AjaxResult result) {
        List<KnowledgeVo> items = (List<KnowledgeVo>) result.get(AjaxResult.DATA_TAG);
        return items.get(0).getName();
    }

    @Configuration
    @EnableCaching
    static class TestConfig {
        @Bean
        CacheManager cacheManager() {
            return new ConcurrentMapCacheManager("knowledge");
        }

        @Bean
        KnowledgeService knowledgeService() {
            return mock(KnowledgeService.class);
        }

        @Bean
        KnowledgeFileService knowledgeFileService() {
            return mock(KnowledgeFileService.class);
        }

        @Bean
        AgentKnowledgeService agentKnowledgeService() {
            return mock(AgentKnowledgeService.class);
        }

        @Bean
        FileService fileService() {
            return mock(FileService.class);
        }

        @Bean
        KnowledgeController knowledgeController(KnowledgeService knowledgeService,
                                                KnowledgeFileService knowledgeFileService,
                                                AgentKnowledgeService agentKnowledgeService,
                                                FileService fileService) {
            return new KnowledgeController(
                    knowledgeService,
                    knowledgeFileService,
                    agentKnowledgeService,
                    fileService);
        }
    }
}
