package com.starsea.ai.chunking.indexing;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class SpringAiChunkVectorGatewayTest {

    @Test
    void creates_stable_documents_with_only_simple_whitelisted_metadata() {
        VectorStore vectorStore = mock(VectorStore.class);
        SpringAiChunkVectorGateway gateway = new SpringAiChunkVectorGateway(
                vectorStore, new ObjectMapper());
        UUID chunkPublicId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID documentPublicId = UUID.fromString("22222222-2222-2222-2222-222222222222");

        gateway.add(List.of(new ChunkVectorGateway.VectorDocument(
                chunkPublicId,
                "标题：指南 > 网络\n\n重置密码。",
                1L,
                10L,
                20L,
                documentPublicId,
                3,
                "md",
                List.of("指南", "网络"))));

        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).add(captor.capture());
        Document document = captor.getValue().get(0);
        assertEquals(chunkPublicId.toString(), document.getId());
        assertEquals("标题：指南 > 网络\n\n重置密码。", document.getText());
        assertEquals(Set.of("tenantId", "knowledgeId", "fileId", "documentPublicId",
                        "documentChunkId", "chunkIndex", "fileType", "sectionPath"),
                document.getMetadata().keySet());
        assertEquals(1L, document.getMetadata().get("tenantId"));
        assertEquals(10L, document.getMetadata().get("knowledgeId"));
        assertEquals(20L, document.getMetadata().get("fileId"));
        assertEquals(documentPublicId.toString(), document.getMetadata().get("documentPublicId"));
        assertEquals(chunkPublicId.toString(), document.getMetadata().get("documentChunkId"));
        assertEquals(3, document.getMetadata().get("chunkIndex"));
        assertEquals("md", document.getMetadata().get("fileType"));
        assertEquals("[\"指南\",\"网络\"]", document.getMetadata().get("sectionPath"));
        assertTrue(document.getMetadata().values().stream().allMatch(value ->
                value instanceof String || value instanceof Number || value instanceof Boolean));
    }

    @Test
    void deletes_every_stable_public_id_as_a_string() {
        VectorStore vectorStore = mock(VectorStore.class);
        SpringAiChunkVectorGateway gateway = new SpringAiChunkVectorGateway(
                vectorStore, new ObjectMapper());
        UUID first = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID second = UUID.fromString("22222222-2222-2222-2222-222222222222");

        gateway.deleteAll(List.of(first, second));

        verify(vectorStore).delete(List.of(first.toString(), second.toString()));
    }
}
