package com.starsea.ai.chunking.model;

import java.nio.file.Path;
import java.util.UUID;

/** File metadata shared by every document-structure parser. */
public record FileResource(
        long tenantId,
        long knowledgeId,
        long fileId,
        UUID filePublicId,
        String fileName,
        String fileType,
        Path path) {
}
