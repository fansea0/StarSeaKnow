package com.starsea.ai.chunking.extraction;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class PlainTextExtractor implements DocumentTextExtractor {

    private static final Set<String> MEDIA_TYPES = Set.of(
            "text/plain", "text/markdown", "text/x-markdown", "text/x-web-markdown",
            "text/csv", "application/csv", "application/json", "application/x-ndjson",
            "text/x-log");
    private final long maxSourceBytes;
    private final int maxOutputCharacters;

    public PlainTextExtractor(
            @Value("${chunking.extraction.max-source-bytes:52428800}") long maxSourceBytes,
            @Value("${chunking.extraction.max-output-characters:10000000}") int maxOutputCharacters) {
        this.maxSourceBytes = maxSourceBytes;
        this.maxOutputCharacters = maxOutputCharacters;
    }

    @Override public String id() { return "plain-text"; }
    @Override public String version() { return "1"; }
    @Override public int priority() { return 300; }
    @Override public Set<String> supportedMediaTypes() { return MEDIA_TYPES; }

    @Override
    public ExtractedText extract(Path path, ExtractionCapability capability) {
        try {
            long size = Files.size(path);
            if (size > maxSourceBytes) {
                throw failure(FailureReason.SOURCE_TOO_LARGE, "Source document exceeds the extraction size limit");
            }
            byte[] bytes = Files.readAllBytes(path);
            Decoded decoded = decode(bytes);
            if (decoded.text().codePointCount(0, decoded.text().length()) > maxOutputCharacters) {
                throw failure(FailureReason.OUTPUT_TOO_LARGE, "Extracted text exceeds the output limit");
            }
            if (decoded.text().isBlank()) {
                throw failure(FailureReason.NO_TEXT, "No chunkable text was extracted");
            }
            return new ExtractedText(decoded.text(), capability.detectedMediaType(), id(), version(),
                    List.of(new SourceSpan(0, decoded.text().length(), Map.of("encoding", decoded.charset()))),
                    Map.of("encoding", decoded.charset()));
        } catch (ExtractionException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ExtractionException(FailureReason.CORRUPT,
                    "Plain text source cannot be read", exception);
        }
    }

    private Decoded decode(byte[] original) {
        Charset charset = StandardCharsets.UTF_8;
        int offset = 0;
        if (startsWith(original, (byte) 0xEF, (byte) 0xBB, (byte) 0xBF)) {
            offset = 3;
        } else if (startsWith(original, (byte) 0xFF, (byte) 0xFE, (byte) 0x00, (byte) 0x00)) {
            charset = Charset.forName("UTF-32LE");
            offset = 4;
        } else if (startsWith(original, (byte) 0x00, (byte) 0x00, (byte) 0xFE, (byte) 0xFF)) {
            charset = Charset.forName("UTF-32BE");
            offset = 4;
        } else if (startsWith(original, (byte) 0xFF, (byte) 0xFE)) {
            charset = StandardCharsets.UTF_16LE;
            offset = 2;
        } else if (startsWith(original, (byte) 0xFE, (byte) 0xFF)) {
            charset = StandardCharsets.UTF_16BE;
            offset = 2;
        }
        try {
            String text = charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(original, offset, original.length - offset)).toString();
            return new Decoded(text, charset.name());
        } catch (CharacterCodingException exception) {
            throw new ExtractionException(FailureReason.UNRELIABLE_ENCODING,
                    "Text encoding could not be decoded reliably", exception);
        }
    }

    private boolean startsWith(byte[] value, byte... prefix) {
        return value.length >= prefix.length
                && Arrays.equals(Arrays.copyOf(value, prefix.length), prefix);
    }

    private ExtractionException failure(FailureReason reason, String message) {
        return new ExtractionException(reason, message);
    }

    private record Decoded(String text, String charset) {}
}
