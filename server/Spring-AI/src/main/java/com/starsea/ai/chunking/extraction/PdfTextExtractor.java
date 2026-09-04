package com.starsea.ai.chunking.extraction;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class PdfTextExtractor implements DocumentTextExtractor {

    private final long maxSourceBytes;
    private final int maxOutputCharacters;
    private final long timeoutMillis;
    private final long maxStorageBytes;

    @Autowired
    public PdfTextExtractor(
            @Value("${chunking.extraction.max-source-bytes:52428800}") long maxSourceBytes,
            @Value("${chunking.extraction.max-output-characters:10000000}") int maxOutputCharacters,
            @Value("${chunking.extraction.timeout:30s}") java.time.Duration timeout,
            @Value("${chunking.extraction.pdf-max-storage-bytes:209715200}") long maxStorageBytes) {
        this(maxSourceBytes, maxOutputCharacters, timeout.toMillis(), maxStorageBytes);
    }

    public PdfTextExtractor(long maxSourceBytes, int maxOutputCharacters, long timeoutMillis) {
        this(maxSourceBytes, maxOutputCharacters, timeoutMillis,
                maxSourceBytes > Long.MAX_VALUE / 4 ? Long.MAX_VALUE : maxSourceBytes * 4);
    }

    public PdfTextExtractor(long maxSourceBytes, int maxOutputCharacters, long timeoutMillis,
                            long maxStorageBytes) {
        this.maxSourceBytes = maxSourceBytes;
        this.maxOutputCharacters = maxOutputCharacters;
        this.timeoutMillis = timeoutMillis;
        this.maxStorageBytes = maxStorageBytes;
    }

    @Override public String id() { return "pdfbox"; }
    @Override public String version() { return "3.0.3"; }
    @Override public int priority() { return 200; }
    @Override public Set<String> supportedMediaTypes() { return Set.of("application/pdf"); }

    @Override
    public ExtractedText extract(Path path, ExtractionCapability capability) {
        requireSourceLimit(path);
        requireStorageLimit(path);
        long deadline = nanoTime() + timeoutMillis * 1_000_000L;
        try (PDDocument document = Loader.loadPDF(path.toFile(),
                MemoryUsageSetting.setupTempFileOnly(maxStorageBytes).streamCache)) {
            if (document.isEncrypted()) {
                throw new ExtractionException(FailureReason.ENCRYPTED,
                        "The PDF is encrypted and cannot be parsed");
            }
            StringBuilder text = new StringBuilder();
            List<SourceSpan> spans = new ArrayList<>();
            for (int page = 1; page <= document.getNumberOfPages(); page++) {
                checkDeadline(deadline);
                PDFTextStripper stripper = new PDFTextStripper();
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String pageText = stripper.getText(document);
                checkDeadline(deadline);
                if (!pageText.isEmpty()) {
                    int start = text.length();
                    text.append(pageText);
                    spans.add(new SourceSpan(start, text.length(), Map.of("page", page)));
                }
                if (text.codePointCount(0, text.length()) > maxOutputCharacters) {
                    throw new ExtractionException(FailureReason.OUTPUT_TOO_LARGE,
                            "Extracted PDF text exceeds the output limit");
                }
            }
            checkDeadline(deadline);
            if (text.toString().isBlank()) {
                throw new ExtractionException(FailureReason.NO_TEXT,
                        "No text was extracted from the PDF; OCR may be required");
            }
            return new ExtractedText(text.toString(), capability.detectedMediaType(), id(), version(),
                    spans, Map.of("pageCount", document.getNumberOfPages()));
        } catch (InvalidPasswordException exception) {
            throw new ExtractionException(FailureReason.ENCRYPTED,
                    "The PDF is encrypted and cannot be parsed", exception);
        } catch (ExtractionException exception) {
            throw exception;
        } catch (IOException exception) {
            if (isStorageLimit(exception)) {
                throw new ExtractionException(FailureReason.LIMIT_EXCEEDED,
                        "PDF temporary storage exceeds the extraction limit", exception);
            }
            throw new ExtractionException(FailureReason.CORRUPT,
                    "The PDF is damaged or cannot be parsed", exception);
        }
    }

    protected long nanoTime() {
        return System.nanoTime();
    }

    private void checkDeadline(long deadline) {
        if (nanoTime() > deadline) {
            throw new ExtractionException(FailureReason.TIMEOUT,
                    "PDF extraction exceeded the time limit");
        }
    }

    private boolean isStorageLimit(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && message.contains("Maximum allowed scratch file memory exceeded")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private void requireSourceLimit(Path path) {
        try {
            if (Files.size(path) > maxSourceBytes) {
                throw new ExtractionException(FailureReason.SOURCE_TOO_LARGE,
                        "Source document exceeds the extraction size limit");
            }
        } catch (IOException exception) {
            throw new ExtractionException(FailureReason.CORRUPT,
                    "The PDF source cannot be read", exception);
        }
    }

    private void requireStorageLimit(Path path) {
        try {
            if (Files.size(path) > maxStorageBytes) {
                throw new ExtractionException(FailureReason.LIMIT_EXCEEDED,
                        "PDF storage requirements exceed the extraction limit");
            }
        } catch (IOException exception) {
            throw new ExtractionException(FailureReason.CORRUPT,
                    "The PDF source cannot be read", exception);
        }
    }
}
