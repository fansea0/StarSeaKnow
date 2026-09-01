package com.starsea.ai.chunking.indexing;

import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/** Uses the stable chunk public ID as the vector document ID. */
@FunctionalInterface
public interface ChunkVectorGateway {

    void delete(UUID publicId);
}

@Component
final class SpringAiChunkVectorGateway implements ChunkVectorGateway {

    private final VectorStore vectorStore;

    SpringAiChunkVectorGateway(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    @Override
    public void delete(UUID publicId) {
        vectorStore.delete(List.of(publicId.toString()));
    }
}
