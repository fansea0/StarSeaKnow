package com.starsea.ai.chunking.extraction;

import com.starsea.ai.chunking.general.UnicodeText;
import org.apache.tika.parser.txt.CharsetDetector;
import org.apache.tika.parser.txt.CharsetMatch;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.CoderResult;
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
        if (maxSourceBytes < 1 || maxSourceBytes > Integer.MAX_VALUE - 8L) {
            throw new IllegalArgumentException("maxSourceBytes is outside the supported byte-array range");
        }
        if (maxOutputCharacters < 1) {
            throw new IllegalArgumentException("maxOutputCharacters must be positive");
        }
        this.maxSourceBytes = maxSourceBytes;
        this.maxOutputCharacters = maxOutputCharacters;
    }

    @Override public String id() { return "plain-text"; }
    @Override public String version() { return "3"; }
    @Override public int priority() { return 300; }
    @Override public Set<String> supportedMediaTypes() { return MEDIA_TYPES; }

    @Override
    public ExtractedText extract(Path path, ExtractionCapability capability) {
        try {
            long size = sourceSize(path);
            if (size > maxSourceBytes) {
                throw failure(FailureReason.SOURCE_TOO_LARGE, "Source document exceeds the extraction size limit");
            }
            byte[] bytes = readBounded(path, size);
            Decoded decoded = decode(bytes);
            if (UnicodeText.isBlank(decoded.text())) {
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

    protected long sourceSize(Path path) throws IOException {
        return Files.size(path);
    }

    protected InputStream openSource(Path path) throws IOException {
        return Files.newInputStream(path);
    }

    private byte[] readBounded(Path path, long observedSize) throws IOException {
        int initialCapacity = Math.toIntExact(Math.min(observedSize, maxSourceBytes));
        ByteArrayOutputStream output = new ByteArrayOutputStream(initialCapacity);
        byte[] buffer = new byte[8192];
        long copied = 0;
        try (InputStream input = openSource(path)) {
            while (true) {
                long remaining = maxSourceBytes - copied;
                int requested = remaining >= buffer.length
                        ? buffer.length : Math.toIntExact(remaining + 1);
                int read = input.read(buffer, 0, requested);
                if (read < 0) break;
                if (read == 0) continue;
                if (read > remaining) {
                    throw failure(FailureReason.SOURCE_TOO_LARGE,
                            "Source document exceeds the extraction size limit");
                }
                output.write(buffer, 0, read);
                copied += read;
            }
        }
        return output.toByteArray();
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
            return requireDecoded(original, offset, charset);
        }
        Attempt utf8 = decodeAttempt(original, 0, StandardCharsets.UTF_8, true);
        if (utf8 != null) {
            requireWithinLimit(utf8, StandardCharsets.UTF_8);
            return utf8.decoded();
        }

        Charset gb18030 = Charset.forName("GB18030");
        Charset windows1252 = Charset.forName("windows-1252");
        Attempt gb = decodeAttempt(original, 0, gb18030, false);
        Attempt western = decodeAttempt(original, 0, windows1252, false);
        Charset selected = selectLegacyCharset(original, gb, western, gb18030, windows1252);
        return requireDecoded(original, 0, selected);
    }

    private Charset selectLegacyCharset(byte[] original, Attempt gb, Attempt western,
                                        Charset gb18030, Charset windows1252) {
        if (gb == null && western == null) throw unreliableEncoding();
        if (gb == null) return requireWithinLimit(western, windows1252);
        if (western == null) return requireWithinLimit(gb, gb18030);

        DetectorConfidence confidence = detectorConfidence(original);
        Analysis gbAnalysis = gb.analysis();
        Analysis westernAnalysis = western.analysis();
        if (gbAnalysis.cjkCodePoints() >= 2
                && gbAnalysis.cjkCodePoints() * 2 >= gbAnalysis.letterCodePoints()
                && confidence.gb18030() >= confidence.windows1252()) {
            if (confidence.gb18030() <= 10
                    && westernAnalysis.latinCodePoints() == westernAnalysis.codePoints()) {
                throw unreliableEncoding();
            }
            return requireWithinLimit(gb, gb18030);
        }
        if (confidence.windows1252() >= 20
                || gbAnalysis.cjkCodePoints() < 2
                && westernAnalysis.latinCodePoints() > 0) {
            return requireWithinLimit(western, windows1252);
        }
        throw unreliableEncoding();
    }

    private Charset requireWithinLimit(Attempt attempt, Charset charset) {
        if (attempt.tooLarge()) {
            throw failure(FailureReason.OUTPUT_TOO_LARGE,
                    "Extracted text exceeds the output limit");
        }
        return charset;
    }

    private DetectorConfidence detectorConfidence(byte[] original) {
        byte[] sample = original.length <= 12_000
                ? original : Arrays.copyOf(original, 12_000);
        int gb18030 = 0;
        int windows1252 = 0;
        for (CharsetMatch match : new CharsetDetector().setText(sample).detectAll()) {
            String name = match.getName().toUpperCase(java.util.Locale.ROOT);
            if (name.equals("GB18030")) gb18030 = Math.max(gb18030, match.getConfidence());
            if (name.equals("WINDOWS-1252") || name.equals("ISO-8859-1")
                    || name.equals("ISO-8859-9")) {
                windows1252 = Math.max(windows1252, match.getConfidence());
            }
        }
        return new DetectorConfidence(gb18030, windows1252);
    }

    private Decoded requireDecoded(byte[] original, int offset, Charset charset) {
        Attempt attempt = decodeAttempt(original, offset, charset, true);
        if (attempt == null) throw unreliableEncoding();
        requireWithinLimit(attempt, charset);
        return attempt.decoded();
    }

    private Attempt decodeAttempt(byte[] original, int offset, Charset charset, boolean retainText) {
        CharsetDecoder decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        ByteBuffer input = ByteBuffer.wrap(original, offset, original.length - offset);
        CharBuffer output = CharBuffer.allocate(2048);
        int retainedCapacity = (int) Math.min(original.length - offset,
                Math.min((long) maxOutputCharacters * 2L, Integer.MAX_VALUE - 8L));
        StringBuilder text = retainText ? new StringBuilder(retainedCapacity) : null;
        MutableAnalysis analysis = new MutableAnalysis();
        boolean endOfInput = false;
        while (!endOfInput) {
            CoderResult result = decoder.decode(input, output, true);
            if (!consume(output, text, analysis)) return null;
            if (analysis.codePoints > maxOutputCharacters) {
                return new Attempt(analysis.freeze(), null, true);
            }
            if (result.isError()) return null;
            endOfInput = result.isUnderflow();
        }
        while (true) {
            CoderResult result = decoder.flush(output);
            if (!consume(output, text, analysis)) return null;
            if (analysis.codePoints > maxOutputCharacters) {
                return new Attempt(analysis.freeze(), null, true);
            }
            if (result.isError()) return null;
            if (result.isUnderflow()) break;
        }
        if (analysis.pendingHighSurrogate != 0) return null;
        Decoded decoded = retainText ? new Decoded(text.toString(), charset.name()) : null;
        return new Attempt(analysis.freeze(), decoded, false);
    }

    private boolean consume(CharBuffer buffer, StringBuilder text, MutableAnalysis analysis) {
        buffer.flip();
        if (text != null) text.append(buffer);
        while (buffer.hasRemaining()) {
            char current = buffer.get();
            if (analysis.pendingHighSurrogate != 0) {
                if (!Character.isLowSurrogate(current)) return false;
                int codePoint = Character.toCodePoint(analysis.pendingHighSurrogate, current);
                analysis.pendingHighSurrogate = 0;
                if (!accept(codePoint, analysis)) return false;
            } else if (Character.isHighSurrogate(current)) {
                analysis.pendingHighSurrogate = current;
            } else if (Character.isLowSurrogate(current) || !accept(current, analysis)) {
                return false;
            }
        }
        buffer.clear();
        return true;
    }

    private boolean accept(int codePoint, MutableAnalysis analysis) {
        int type = Character.getType(codePoint);
        if (codePoint == 0 || type == Character.UNASSIGNED
                || type == Character.PRIVATE_USE || type == Character.SURROGATE) return false;
        if (type == Character.CONTROL && codePoint != '\n' && codePoint != '\r'
                && codePoint != '\t' && codePoint != '\f') return false;
        analysis.codePoints++;
        if (isCjk(codePoint)) analysis.cjkCodePoints++;
        if (Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.LATIN) {
            analysis.latinCodePoints++;
        }
        if (Character.isLetter(codePoint)) analysis.letterCodePoints++;
        return true;
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

    private ExtractionException unreliableEncoding() {
        return failure(FailureReason.UNRELIABLE_ENCODING,
                "Text encoding could not be decoded reliably");
    }

    private record Decoded(String text, String charset) {}
    private record DetectorConfidence(int gb18030, int windows1252) {}
    private record Analysis(int codePoints, int cjkCodePoints,
                            int latinCodePoints, int letterCodePoints) {}
    private record Attempt(Analysis analysis, Decoded decoded, boolean tooLarge) {}

    private static final class MutableAnalysis {
        private int codePoints;
        private int cjkCodePoints;
        private int latinCodePoints;
        private int letterCodePoints;
        private char pendingHighSurrogate;

        private Analysis freeze() {
            return new Analysis(codePoints, cjkCodePoints, latinCodePoints, letterCodePoints);
        }
    }
}
