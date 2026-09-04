package com.starsea.ai.chunking.extraction;

import com.starsea.ai.chunking.general.UnicodeText;
import org.apache.tika.config.TikaConfig;
import org.apache.tika.config.TikaTaskTimeout;
import org.apache.tika.exception.CorruptedFileException;
import org.apache.tika.exception.EncryptedDocumentException;
import org.apache.tika.exception.TikaTimeoutException;
import org.apache.tika.exception.WriteLimitReachedException;
import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.extractor.ParsingEmbeddedDocumentExtractor;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.io.TemporaryResources;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.apache.commons.io.input.CloseShieldInputStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.io.InputStream;
import java.io.FilterInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Component
public class TikaDocumentTextExtractor implements DocumentTextExtractor {

    private static final Set<String> MEDIA_TYPES = Set.of(
            "text/html", "application/xhtml+xml", "application/rtf", "text/rtf",
            "application/epub+zip", "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.ms-powerpoint",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "application/x-tika-ooxml-protected");
    private final long maxSourceBytes;
    private final int maxOutputCharacters;
    private final long timeoutMillis;
    private final int maxEmbeddedDepth;
    private final long maxDecompressedBytes;
    private final TikaConfig tikaConfig;

    @Autowired
    public TikaDocumentTextExtractor(
            @Value("${chunking.extraction.max-source-bytes:52428800}") long maxSourceBytes,
            @Value("${chunking.extraction.max-output-characters:10000000}") int maxOutputCharacters,
            @Value("${chunking.extraction.timeout:30s}") java.time.Duration timeout,
            @Value("${chunking.extraction.max-embedded-depth:8}") int maxEmbeddedDepth,
            @Value("${chunking.extraction.max-decompressed-bytes:209715200}") long maxDecompressedBytes) {
        this(maxSourceBytes, maxOutputCharacters, timeout.toMillis(), maxEmbeddedDepth,
                maxDecompressedBytes);
    }

    public TikaDocumentTextExtractor(long maxSourceBytes, int maxOutputCharacters,
                                     long timeoutMillis, int maxEmbeddedDepth) {
        this(maxSourceBytes, maxOutputCharacters, timeoutMillis, maxEmbeddedDepth,
                Math.max(maxSourceBytes, maxSourceBytes * 4));
    }

    public TikaDocumentTextExtractor(long maxSourceBytes, int maxOutputCharacters,
                                     long timeoutMillis, int maxEmbeddedDepth,
                                     long maxDecompressedBytes) {
        this.maxSourceBytes = maxSourceBytes;
        this.maxOutputCharacters = maxOutputCharacters;
        this.timeoutMillis = timeoutMillis;
        this.maxEmbeddedDepth = maxEmbeddedDepth;
        this.maxDecompressedBytes = maxDecompressedBytes;
        this.tikaConfig = TikaConfig.getDefaultConfig();
    }

    @Override public String id() { return "tika"; }
    @Override public String version() { return "3.0.0"; }
    @Override public int priority() { return 100; }
    @Override public Set<String> supportedMediaTypes() { return MEDIA_TYPES; }

    @Override
    public ExtractedText extract(Path path, ExtractionCapability capability) {
        requireSourceLimit(path);
        long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
        ExtractionBudget budget = new ExtractionBudget();
        if (isSupportedZipContainer(capability.detectedMediaType())) {
            validateArchiveExpansion(path, deadline, budget);
        }
        Metadata metadata = new Metadata();
        metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, path.getFileName().toString());
        metadata.set(TikaCoreProperties.CONTENT_TYPE_HINT, capability.detectedMediaType());
        ParseContext context = new ParseContext();
        long remainingMillis = Math.max(1L, (deadline - System.nanoTime()) / 1_000_000L);
        context.set(TikaTaskTimeout.class, new TikaTaskTimeout(remainingMillis));
        context.set(EmbeddedDocumentExtractor.class,
                new DepthLimitedEmbeddedExtractor(context, budget));
        BodyContentHandler handler = new BodyContentHandler(maxOutputCharacters);
        try (TikaInputStream input = TikaInputStream.get(path)) {
            new AutoDetectParser(tikaConfig).parse(input, handler, metadata, context);
            budget.requireWithinLimit();
            if (System.nanoTime() > deadline) {
                throw new ExtractionException(FailureReason.TIMEOUT,
                        "Document extraction exceeded the time limit");
            }
            String text = handler.toString();
            if (UnicodeText.isBlank(text)) {
                throw new ExtractionException(FailureReason.NO_TEXT,
                        "No chunkable text was extracted");
            }
            return new ExtractedText(text, capability.detectedMediaType(), id(), version(),
                    List.of(new SourceSpan(0, text.length(), Map.of("document", 1))),
                    safeMetadata(metadata));
        } catch (ExtractionException exception) {
            throw exception;
        } catch (Exception exception) {
            throw mapFailure(exception, budget);
        }
    }

    private Map<String, Object> safeMetadata(Metadata metadata) {
        String title = metadata.get(TikaCoreProperties.TITLE);
        return title == null || title.isBlank() ? Map.of() : Map.of("title", title);
    }

    private ExtractionException mapFailure(Exception exception, ExtractionBudget budget) {
        if (budget.limitExceeded) {
            return new ExtractionException(FailureReason.LIMIT_EXCEEDED,
                    "Expanded or nested document data exceeds the extraction limit", exception);
        }
        if (WriteLimitReachedException.isWriteLimitReached(exception)) {
            return new ExtractionException(FailureReason.OUTPUT_TOO_LARGE,
                    "Extracted text exceeds the output limit", exception);
        }
        Throwable current = exception;
        while (current != null) {
            if (current instanceof EncryptedDocumentException) {
                return new ExtractionException(FailureReason.ENCRYPTED,
                        "The document is encrypted and cannot be parsed", exception);
            }
            if (current instanceof TikaTimeoutException) {
                return new ExtractionException(FailureReason.TIMEOUT,
                        "Document extraction exceeded the time limit", exception);
            }
            if (current instanceof CorruptedFileException) {
                return new ExtractionException(FailureReason.CORRUPT,
                        "The document is damaged or cannot be parsed", exception);
            }
            if (current instanceof DecompressionLimitException) {
                return new ExtractionException(FailureReason.LIMIT_EXCEEDED,
                        "Expanded document data exceeds the extraction limit", exception);
            }
            if (current instanceof ExtractionException extractionException) {
                return extractionException;
            }
            current = current.getCause();
        }
        return new ExtractionException(FailureReason.CORRUPT,
                "The document is damaged or cannot be parsed", exception);
    }

    private void requireSourceLimit(Path path) {
        try {
            if (Files.size(path) > maxSourceBytes) {
                throw new ExtractionException(FailureReason.SOURCE_TOO_LARGE,
                        "Source document exceeds the extraction size limit");
            }
        } catch (IOException exception) {
            throw new ExtractionException(FailureReason.CORRUPT,
                    "The document source cannot be read", exception);
        }
    }

    private boolean isSupportedZipContainer(String mediaType) {
        return "application/epub+zip".equals(mediaType)
                || mediaType != null && mediaType.startsWith(
                        "application/vnd.openxmlformats-officedocument.");
    }

    private void validateArchiveExpansion(Path path, long deadline, ExtractionBudget budget) {
        try (InputStream raw = Files.newInputStream(path)) {
            byte[] signature = raw.readNBytes(4);
            if (signature.length < 4 || signature[0] != 'P' || signature[1] != 'K') {
                return;
            }
        } catch (IOException exception) {
            throw new ExtractionException(FailureReason.CORRUPT,
                    "The document source cannot be read", exception);
        }
        byte[] buffer = new byte[8192];
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(path))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                int read;
                while ((read = zip.read(buffer)) >= 0) {
                    budget.consume(read);
                    if (System.nanoTime() > deadline) {
                        throw new ExtractionException(FailureReason.TIMEOUT,
                                "Document extraction exceeded the time limit");
                    }
                }
                zip.closeEntry();
            }
        } catch (ExtractionException exception) {
            throw exception;
        } catch (DecompressionLimitException exception) {
            throw new ExtractionException(FailureReason.LIMIT_EXCEEDED,
                    "Expanded document data exceeds the extraction limit", exception);
        } catch (IOException exception) {
            throw new ExtractionException(FailureReason.CORRUPT,
                    "The document archive is damaged or cannot be parsed", exception);
        }
    }

    private final class DepthLimitedEmbeddedExtractor implements EmbeddedDocumentExtractor {
        private final ParsingEmbeddedDocumentExtractor delegate;
        private final ExtractionBudget budget;
        private int depth;

        private DepthLimitedEmbeddedExtractor(ParseContext context, ExtractionBudget budget) {
            this.delegate = new ParsingEmbeddedDocumentExtractor(context);
            this.budget = budget;
        }

        @Override
        public boolean shouldParseEmbedded(Metadata metadata) {
            boolean requested = delegate.shouldParseEmbedded(metadata);
            if (requested && depth >= maxEmbeddedDepth) {
                budget.limitExceeded = true;
                throw new ExtractionException(FailureReason.LIMIT_EXCEEDED,
                        "Embedded document nesting exceeds the extraction limit");
            }
            return requested;
        }

        @Override
        public void parseEmbedded(InputStream stream, ContentHandler handler, Metadata metadata,
                                  boolean outputHtml) throws SAXException, IOException {
            if (depth >= maxEmbeddedDepth) {
                budget.limitExceeded = true;
                throw new ExtractionException(FailureReason.LIMIT_EXCEEDED,
                        "Embedded document nesting exceeds the extraction limit");
            }
            depth++;
            try (TemporaryResources resources = new TemporaryResources();
                 TikaInputStream bounded = TikaInputStream.get(
                         new DecompressionLimitedInputStream(CloseShieldInputStream.wrap(stream), budget),
                         resources, metadata)) {
                bounded.getPath();
                delegate.parseEmbedded(bounded, handler, metadata, outputHtml);
            } finally {
                depth--;
            }
        }
    }

    private final class DecompressionLimitedInputStream extends FilterInputStream {
        private final ExtractionBudget budget;

        private DecompressionLimitedInputStream(InputStream input, ExtractionBudget budget) {
            super(input);
            this.budget = budget;
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0) increment(1);
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            int read = super.read(bytes, offset, length);
            if (read > 0) increment(read);
            return read;
        }

        private void increment(int amount) throws DecompressionLimitException {
            budget.consume(amount);
        }
    }

    private final class ExtractionBudget {
        private long expandedBytes;
        private boolean limitExceeded;

        private void consume(long amount) throws DecompressionLimitException {
            if (amount > maxDecompressedBytes - expandedBytes) {
                limitExceeded = true;
                throw new DecompressionLimitException();
            }
            expandedBytes += amount;
        }

        private void requireWithinLimit() {
            if (limitExceeded) {
                throw new ExtractionException(FailureReason.LIMIT_EXCEEDED,
                        "Expanded or nested document data exceeds the extraction limit");
            }
        }
    }

    private static final class DecompressionLimitException extends IOException {
    }
}
