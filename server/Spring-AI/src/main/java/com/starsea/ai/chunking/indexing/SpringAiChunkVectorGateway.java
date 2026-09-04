package com.starsea.ai.chunking.indexing;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Component
public final class SpringAiChunkVectorGateway implements ChunkVectorGateway {

    private final VectorStore vectorStore;
    private final ObjectMapper objectMapper;

    public SpringAiChunkVectorGateway(VectorStore vectorStore, ObjectMapper objectMapper) {
        this.vectorStore = Objects.requireNonNull(vectorStore, "vectorStore");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    public void add(List<VectorDocument> documents) {
        List<Document> springDocuments = documents.stream()
                .map(this::toSpringDocument)
                .toList();
        vectorStore.add(springDocuments);
    }

    @Override
    public void deleteAll(List<UUID> vectorIds) {
        vectorStore.delete(vectorIds.stream().map(UUID::toString).toList());
    }

    @Override
    public void delete(UUID vectorId) {
        deleteAll(List.of(vectorId));
    }

    private Document toSpringDocument(VectorDocument source) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("tenantId", source.tenantId());
        metadata.put("knowledgeId", source.knowledgeId());
        metadata.put("fileId", source.fileId());
        metadata.put("documentPublicId", source.documentPublicId().toString());
        metadata.put("documentChunkId", source.publicId().toString());
        metadata.put("chunkIndex", source.chunkIndex());
        metadata.put("fileType", source.fileType());
        metadata.put("sectionPath", sectionPathJson(source.sectionPath()));
        return new Document(source.vectorId().toString(), source.indexContent(), metadata);
    }

    private String sectionPathJson(List<String> sectionPath) {
        try {
            return objectMapper.writeValueAsString(sectionPath);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("sectionPath cannot be serialized", exception);
        }
    }
}
