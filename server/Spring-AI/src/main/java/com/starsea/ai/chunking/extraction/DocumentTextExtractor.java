package com.starsea.ai.chunking.extraction;

import org.apache.tika.config.TikaConfig;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

public interface DocumentTextExtractor {

    String id();

    String version();

    int priority();

    Set<String> supportedMediaTypes();

    default ExtractionCapability probe(Path path, String suppliedType) {
        if (path == null || !Files.isRegularFile(path) || !Files.isReadable(path)) {
            return ExtractionCapability.unavailable(null, "Source file is not readable");
        }
        String mediaType;
        try {
            mediaType = detectMediaType(path, suppliedType);
        } catch (IOException exception) {
            return ExtractionCapability.unavailable(null, "Source media type cannot be detected");
        }
        if (!MediaTypeCanonicalizer.supports(this, mediaType)) {
            return ExtractionCapability.unavailable(mediaType, "Media type is not supported by this extractor");
        }
        return new ExtractionCapability(true, mediaType, id(), version(), priority(), null);
    }

    ExtractedText extract(Path path, ExtractionCapability capability);

    static String detectMediaType(Path path, String suppliedType) throws IOException {
        Metadata metadata = new Metadata();
        metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, path.getFileName().toString());
        if (suppliedType != null && !suppliedType.isBlank()) {
            metadata.set(TikaCoreProperties.CONTENT_TYPE_HINT,
                    MediaTypeCanonicalizer.canonicalize(suppliedType));
        }
        try (TikaInputStream input = TikaInputStream.get(path)) {
            return MediaTypeCanonicalizer.canonicalize(TikaConfig.getDefaultConfig().getDetector()
                    .detect(input, metadata).toString());
        }
    }

    enum FailureReason {
        SOURCE_TOO_LARGE,
        DECOMPRESSION_LIMIT,
        LIMIT_EXCEEDED,
        OUTPUT_TOO_LARGE,
        TIMEOUT,
        ENCRYPTED,
        CORRUPT,
        NO_TEXT,
        UNSUPPORTED,
        UNRELIABLE_ENCODING
    }

    final class ExtractionException extends RuntimeException {
        private final FailureReason reason;

        public ExtractionException(FailureReason reason, String message) {
            super(message);
            this.reason = reason;
        }

        public ExtractionException(FailureReason reason, String message, Throwable cause) {
            super(message, cause);
            this.reason = reason;
        }

        public FailureReason reason() {
            return reason;
        }
    }
}
