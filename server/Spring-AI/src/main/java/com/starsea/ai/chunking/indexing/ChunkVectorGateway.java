package com.starsea.ai.chunking.indexing;

import java.util.List;
import java.util.UUID;

/** Uses the stable chunk public ID as the vector document ID. */
@FunctionalInterface
public interface ChunkVectorGateway {

    void delete(UUID publicId);

    default void add(List<VectorDocument> documents) {
        throw new UnsupportedOperationException("Vector addition is not configured");
    }

    default void deleteAll(List<UUID> publicIds) {
        publicIds.forEach(this::delete);
    }

    record VectorDocument(
            UUID publicId,
            String indexContent,
            long tenantId,
            long knowledgeId,
            long fileId,
            UUID documentPublicId,
            int chunkIndex,
            String fileType,
            List<String> sectionPath) {

        public VectorDocument {
            sectionPath = sectionPath == null ? List.of() : List.copyOf(sectionPath);
        }
    }
}
