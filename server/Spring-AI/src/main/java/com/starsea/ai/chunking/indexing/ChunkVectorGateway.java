package com.starsea.ai.chunking.indexing;

import com.starsea.ai.chunking.model.ChunkType;

import java.util.List;
import java.util.UUID;

/** Uses a per-attempt vector ID while retaining the stable chunk public ID as metadata. */
@FunctionalInterface
public interface ChunkVectorGateway {

    void delete(UUID vectorId);

    default void add(List<VectorDocument> documents) {
        throw new UnsupportedOperationException("Vector addition is not configured");
    }

    default void deleteAll(List<UUID> vectorIds) {
        vectorIds.forEach(this::delete);
    }

    record VectorDocument(
            UUID vectorId,
            UUID publicId,
            String indexContent,
            long tenantId,
            long knowledgeId,
            long fileId,
            UUID documentPublicId,
            int chunkIndex,
            String fileType,
            List<String> sectionPath,
            ChunkType chunkType,
            UUID parentChunkPublicId) {

        public VectorDocument {
            vectorId = java.util.Objects.requireNonNull(vectorId, "vectorId");
            publicId = java.util.Objects.requireNonNull(publicId, "publicId");
            sectionPath = sectionPath == null ? List.of() : List.copyOf(sectionPath);
            chunkType = chunkType == null ? ChunkType.SINGLE : chunkType;
            if (chunkType == ChunkType.CHILD) {
                java.util.Objects.requireNonNull(parentChunkPublicId,
                        "A CHILD vector document requires a parent public ID");
            }
        }

        public VectorDocument(UUID publicId, String indexContent, long tenantId,
                              long knowledgeId, long fileId, UUID documentPublicId,
                              int chunkIndex, String fileType, List<String> sectionPath,
                              ChunkType chunkType, UUID parentChunkPublicId) {
            this(publicId, publicId, indexContent, tenantId, knowledgeId, fileId,
                    documentPublicId, chunkIndex, fileType, sectionPath,
                    chunkType, parentChunkPublicId);
        }

        public VectorDocument(UUID vectorId, UUID publicId, String indexContent,
                              long tenantId, long knowledgeId, long fileId,
                              UUID documentPublicId, int chunkIndex, String fileType,
                              List<String> sectionPath) {
            this(vectorId, publicId, indexContent, tenantId, knowledgeId, fileId,
                    documentPublicId, chunkIndex, fileType, sectionPath,
                    ChunkType.SINGLE, null);
        }

        public VectorDocument(UUID publicId, String indexContent, long tenantId,
                              long knowledgeId, long fileId, UUID documentPublicId,
                              int chunkIndex, String fileType, List<String> sectionPath) {
            this(publicId, publicId, indexContent, tenantId, knowledgeId, fileId,
                    documentPublicId, chunkIndex, fileType, sectionPath,
                    ChunkType.SINGLE, null);
        }
    }
}
