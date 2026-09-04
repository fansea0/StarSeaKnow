package com.starsea.ai.chunking.indexing;

import java.nio.file.Path;

@FunctionalInterface
interface SourceHashReader {
    String hash(Path path) throws Exception;
}
