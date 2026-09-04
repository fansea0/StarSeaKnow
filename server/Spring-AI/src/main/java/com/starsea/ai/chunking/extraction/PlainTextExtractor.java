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
    @Override public String version() { return "2"; }
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
        if (offset > 0) {
            Decoded decoded = decodedStrict(original, offset, charset);
            if (plausibleText(decoded.text())) return decoded;
            throw new ExtractionException(FailureReason.UNRELIABLE_ENCODING,
                    "Text encoding could not be decoded reliably");
        }
        Decoded utf8 = tryDecodedStrict(original, StandardCharsets.UTF_8);
        if (utf8 != null && plausibleText(utf8.text())) return utf8;

        List<Decoded> candidates = java.util.stream.Stream.of(
                        tryDecodedStrict(original, Charset.forName("GB18030")),
                        tryDecodedStrict(original, Charset.forName("windows-1252")))
                .filter(java.util.Objects::nonNull)
                .filter(candidate -> plausibleText(candidate.text()))
                .sorted(java.util.Comparator.comparingInt(
                        (Decoded candidate) -> textScore(candidate.text())).reversed())
                .toList();
        if (candidates.isEmpty()) {
            throw new ExtractionException(FailureReason.UNRELIABLE_ENCODING,
                    "Text encoding could not be decoded reliably");
        }
        return candidates.get(0);
    }

    private Decoded tryDecodedStrict(byte[] original, Charset charset) {
        try {
            return decodedStrict(original, 0, charset);
        } catch (ExtractionException ignored) {
            return null;
        }
    }

    private Decoded decodedStrict(byte[] original, int offset, Charset charset) {
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

    private boolean plausibleText(String text) {
        if (text.isEmpty()) return true;
        int acceptable = 0;
        int total = 0;
        for (int offset = 0; offset < text.length();) {
            int codePoint = text.codePointAt(offset);
            int type = Character.getType(codePoint);
            if (codePoint == 0 || type == Character.UNASSIGNED
                    || type == Character.PRIVATE_USE || type == Character.SURROGATE) {
                return false;
            }
            if (type == Character.CONTROL && codePoint != '\n' && codePoint != '\r'
                    && codePoint != '\t' && codePoint != '\f') {
                return false;
            }
            if (!Character.isISOControl(codePoint)
                    || codePoint == '\n' || codePoint == '\r' || codePoint == '\t' || codePoint == '\f') {
                acceptable++;
            }
            total++;
            offset += Character.charCount(codePoint);
        }
        return acceptable * 100 >= total * 85;
    }

    private int textScore(String text) {
        int score = 0;
        for (int codePoint : text.codePoints().toArray()) {
            if (isCjk(codePoint)) score += 6;
            else if (Character.isLetterOrDigit(codePoint)) score += 2;
            else if (Character.isWhitespace(codePoint)) score += 2;
            else score++;
        }
        return score;
    }

    private boolean isCjk(int codePoint) {
        Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
        return script == Character.UnicodeScript.HAN
                || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA
                || script == Character.UnicodeScript.HANGUL;
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
