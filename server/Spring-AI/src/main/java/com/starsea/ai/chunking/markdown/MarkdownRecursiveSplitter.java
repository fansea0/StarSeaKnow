package com.starsea.ai.chunking.markdown;

import com.starsea.ai.chunking.model.BlockType;
import com.starsea.ai.chunking.model.SemanticUnit;
import com.starsea.ai.chunking.model.SourceLocator;
import com.starsea.ai.chunking.model.StructuredBlock;
import com.starsea.ai.chunking.spi.TokenCounter;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Recursively splits only semantic units that cannot fit the configured hard budget. */
public final class MarkdownRecursiveSplitter {

    private static final String SENTENCE_ENDINGS = "。！？!?；;.";

    private final TokenCounter tokenCounter;

    public MarkdownRecursiveSplitter(TokenCounter tokenCounter) {
        this.tokenCounter = Objects.requireNonNull(tokenCounter, "tokenCounter");
    }

    List<SplitPart> split(SemanticUnit unit, int maxTokens) {
        String content = MarkdownSemanticUnitBuilder.render(unit.blocks());
        if (fits(unit.sectionPath(), content, maxTokens)) {
            return List.of(new SplitPart(content, sourceLocator(unit.blocks()), false, endReason(unit)));
        }

        List<String> parts;
        StructuredBlock structuralBlock = unit.blocks().get(unit.blocks().size() - 1);
        if (unit.blocks().size() == 1) {
            parts = switch (structuralBlock.type()) {
                case PARAGRAPH, BLOCK_QUOTE, HTML_BLOCK -> splitSentences(content, unit.sectionPath(), maxTokens);
                case ORDERED_LIST, UNORDERED_LIST -> splitList(content, unit.sectionPath(), maxTokens);
                case TABLE -> splitTable(content, unit.sectionPath(), maxTokens);
                case FENCED_CODE -> splitFencedCode(content, unit.sectionPath(), maxTokens);
                case INDENTED_CODE -> splitLines(content, unit.sectionPath(), maxTokens, "\n");
                default -> splitTokenSafe(content, unit.sectionPath(), maxTokens);
            };
        } else {
            parts = splitGuidedContainer(unit, structuralBlock, maxTokens);
        }

        List<String> nonBlankParts = parts.stream().filter(part -> !part.isBlank()).toList();
        List<SourceLocator> locators = projectSourceLocators(
                unit, structuralBlock.type(), content, nonBlankParts);
        List<SplitPart> result = new ArrayList<>(nonBlankParts.size());
        for (int index = 0; index < nonBlankParts.size(); index++) {
            result.add(new SplitPart(nonBlankParts.get(index), locators.get(index), true,
                    structuralBlock.type() == BlockType.PARAGRAPH
                            ? "SENTENCE_END" : "CONTAINER_END"));
        }
        return List.copyOf(result);
    }

    private List<SourceLocator> projectSourceLocators(SemanticUnit unit, BlockType type,
                                                       String sourceText, List<String> parts) {
        SourceLocator whole = sourceLocator(unit.blocks());
        if (parts.size() <= 1 || whole.startOffset() == null || whole.startLine() == null) {
            return java.util.Collections.nCopies(parts.size(), whole);
        }
        NormalizedText normalizedSource = normalizeLineEndings(sourceText);
        List<SourceLocator> result = new ArrayList<>(parts.size());
        int cursor = 0;
        for (int index = 0; index < parts.size(); index++) {
            String normalizedPart = normalizeLineEndings(parts.get(index)).text();
            String owned = ownedSourceText(type, normalizedSource.text(), normalizedPart,
                    index, parts.size());
            int start = owned.isEmpty() ? -1 : normalizedSource.text().indexOf(owned, cursor);
            if (start < 0) {
                return java.util.Collections.nCopies(parts.size(), whole);
            }
            int end = start + owned.length();
            result.add(project(whole, sourceText,
                    normalizedSource.boundaries().get(start),
                    normalizedSource.boundaries().get(end)));
            cursor = end;
        }
        return List.copyOf(result);
    }

    private NormalizedText normalizeLineEndings(String value) {
        StringBuilder normalized = new StringBuilder(value.length());
        List<Integer> boundaries = new ArrayList<>();
        boundaries.add(0);
        int index = 0;
        while (index < value.length()) {
            char current = value.charAt(index);
            if (current == '\r') {
                normalized.append('\n');
                index += index + 1 < value.length() && value.charAt(index + 1) == '\n' ? 2 : 1;
            } else {
                normalized.append(current);
                index++;
            }
            boundaries.add(index);
        }
        return new NormalizedText(normalized.toString(), List.copyOf(boundaries));
    }

    private String ownedSourceText(BlockType type, String sourceText, String renderedPart,
                                   int index, int partCount) {
        if (type == BlockType.TABLE) {
            int firstLineEnd = sourceText.indexOf('\n');
            int secondLineEnd = firstLineEnd < 0 ? -1 : sourceText.indexOf('\n', firstLineEnd + 1);
            String header = firstLineEnd < 0 ? sourceText
                    : sourceText.substring(0, secondLineEnd < 0 ? sourceText.length() : secondLineEnd);
            if (index > 0 && renderedPart.startsWith(header)) {
                String owned = renderedPart.substring(header.length());
                return owned.startsWith("\n") ? owned.substring(1) : owned;
            }
            return renderedPart;
        }
        if (type == BlockType.FENCED_CODE) {
            int openingEnd = sourceText.indexOf('\n');
            if (openingEnd < 0) {
                return renderedPart;
            }
            String opening = sourceText.substring(0, openingEnd);
            Fence fence = parseOpeningFence(opening);
            if (fence == null) {
                return renderedPart;
            }
            int lastLineStart = sourceText.lastIndexOf('\n');
            String originalLastLine = lastLineStart < 0 ? sourceText : sourceText.substring(lastLineStart + 1);
            boolean ownsClosing = isMatchingClosingFence(originalLastLine, fence);
            String synthesizedClosing = String.valueOf(fence.marker()).repeat(fence.length());
            String body = renderedPart;
            String prefix = opening + "\n";
            if (body.startsWith(prefix)) {
                body = body.substring(prefix.length());
            }
            String renderedSuffix = "\n" + (ownsClosing ? originalLastLine : synthesizedClosing);
            if (body.endsWith(renderedSuffix)) {
                body = body.substring(0, body.length() - renderedSuffix.length());
            }
            if (index == 0) {
                body = prefix + body;
            }
            if (index == partCount - 1 && ownsClosing) {
                body = body + "\n" + originalLastLine;
            }
            return body;
        }
        return renderedPart;
    }

    private SourceLocator project(SourceLocator whole, String sourceText,
                                  int relativeStart, int relativeEnd) {
        int startLine = whole.startLine() + newlineCount(sourceText, 0, relativeStart);
        int lastCharacter = Math.max(relativeStart, relativeEnd - 1);
        int endLine = whole.startLine() + newlineCount(sourceText, 0, lastCharacter);
        return new SourceLocator(
                whole.type(), whole.blockIds(),
                whole.startOffset() + relativeStart,
                whole.startOffset() + relativeEnd,
                startLine, endLine,
                whole.startPage(), whole.endPage(), whole.regions());
    }

    private int newlineCount(String value, int start, int end) {
        int count = 0;
        for (int index = start; index < end; index++) {
            if (value.charAt(index) == '\n') {
                count++;
            }
        }
        return count;
    }

    private List<String> splitGuidedContainer(SemanticUnit unit, StructuredBlock container, int maxTokens) {
        String guide = unit.blocks().get(0).rawText();
        SemanticUnit containerOnly = new SemanticUnit(unit.unitId(), List.of(container), unit.sectionPath(),
                unit.tokenCount(), unit.attributes());
        List<String> containerParts = split(containerOnly, maxTokens).stream().map(SplitPart::content).toList();
        List<String> result = new ArrayList<>();
        if (!containerParts.isEmpty()) {
            String combined = guide + "\n\n" + containerParts.get(0);
            if (fits(unit.sectionPath(), combined, maxTokens)) {
                result.add(combined);
                result.addAll(containerParts.subList(1, containerParts.size()));
                return result;
            }
        }
        for (int firstPartBudget = maxTokens - 1; firstPartBudget > 0; firstPartBudget--) {
            try {
                List<String> smallerParts = split(containerOnly, firstPartBudget).stream()
                        .map(SplitPart::content).toList();
                if (!smallerParts.isEmpty()) {
                    String combined = guide + "\n\n" + smallerParts.get(0);
                    if (fits(unit.sectionPath(), combined, maxTokens)) {
                        result.add(combined);
                        result.addAll(smallerParts.subList(1, smallerParts.size()));
                        return result;
                    }
                }
            } catch (IllegalArgumentException ignored) {
                break;
            }
        }
        result.addAll(splitSentences(guide, unit.sectionPath(), maxTokens));
        result.addAll(containerParts);
        return result;
    }

    private List<String> splitSentences(String content, List<String> path, int maxTokens) {
        List<String> sentences = new ArrayList<>();
        int start = 0;
        for (int index = 0; index < content.length(); index++) {
            if (SENTENCE_ENDINGS.indexOf(content.charAt(index)) >= 0) {
                int end = index + 1;
                while (end < content.length() && Character.isWhitespace(content.charAt(end))) {
                    end++;
                }
                sentences.add(content.substring(start, end));
                start = end;
            }
        }
        if (start < content.length()) {
            sentences.add(content.substring(start));
        }
        return packAtoms(sentences, path, maxTokens, "");
    }

    private List<String> splitList(String content, List<String> path, int maxTokens) {
        List<String> items = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : content.split("\\R", -1)) {
            boolean itemStart = line.matches("^\\s*(?:[-+*]|\\d+[.)])\\s+.*");
            if (itemStart && current.length() > 0) {
                items.add(current.toString());
                current.setLength(0);
            }
            if (current.length() > 0) {
                current.append('\n');
            }
            current.append(line);
        }
        if (current.length() > 0) {
            items.add(current.toString());
        }
        return packAtoms(items, path, maxTokens, "\n");
    }

    private List<String> splitTable(String content, List<String> path, int maxTokens) {
        String[] lines = content.split("\\R");
        if (lines.length < 3) {
            return splitLines(content, path, maxTokens, "\n");
        }
        String header = lines[0] + "\n" + lines[1];
        List<String> chunks = new ArrayList<>();
        String current = header;
        for (int index = 2; index < lines.length; index++) {
            String candidate = current + "\n" + lines[index];
            if (fits(path, candidate, maxTokens)) {
                current = candidate;
                continue;
            }
            if (!current.equals(header)) {
                chunks.add(current);
                current = header;
            }
            String rowCandidate = header + "\n" + lines[index];
            if (fits(path, rowCandidate, maxTokens)) {
                current = rowCandidate;
            } else {
                chunks.addAll(splitTokenSafe(lines[index], path, maxTokens, header + "\n"));
            }
        }
        if (!current.equals(header)) {
            chunks.add(current);
        }
        return chunks.isEmpty() ? splitTokenSafe(content, path, maxTokens) : chunks;
    }

    private List<String> splitFencedCode(String content, List<String> path, int maxTokens) {
        String[] lines = content.split("\\R", -1);
        Fence openingFence = parseOpeningFence(lines[0]);
        if (lines.length < 2 || openingFence == null) {
            return splitLines(content, path, maxTokens, "\n");
        }
        String opening = lines[0];
        boolean hasClosingFence = isMatchingClosingFence(lines[lines.length - 1], openingFence);
        String closing = hasClosingFence
                ? lines[lines.length - 1]
                : String.valueOf(openingFence.marker()).repeat(openingFence.length());
        List<String> bodyLines = new ArrayList<>();
        int end = hasClosingFence ? lines.length - 1 : lines.length;
        for (int index = 1; index < end; index++) {
            bodyLines.add(lines[index]);
        }
        String prefix = opening + "\n";
        String suffix = "\n" + closing;
        return packWrappedAtoms(bodyLines, path, maxTokens, prefix, suffix, "\n");
    }

    private Fence parseOpeningFence(String line) {
        int markerStart = leadingSpaces(line);
        if (markerStart > 3 || markerStart >= line.length()) {
            return null;
        }
        char marker = line.charAt(markerStart);
        if (marker != '`' && marker != '~') {
            return null;
        }
        int markerEnd = markerStart;
        while (markerEnd < line.length() && line.charAt(markerEnd) == marker) {
            markerEnd++;
        }
        int length = markerEnd - markerStart;
        if (length < 3 || marker == '`' && line.substring(markerEnd).indexOf('`') >= 0) {
            return null;
        }
        return new Fence(marker, length);
    }

    private boolean isMatchingClosingFence(String line, Fence openingFence) {
        int markerStart = leadingSpaces(line);
        if (markerStart > 3 || markerStart >= line.length() || line.charAt(markerStart) != openingFence.marker()) {
            return false;
        }
        int markerEnd = markerStart;
        while (markerEnd < line.length() && line.charAt(markerEnd) == openingFence.marker()) {
            markerEnd++;
        }
        if (markerEnd - markerStart < openingFence.length()) {
            return false;
        }
        for (int index = markerEnd; index < line.length(); index++) {
            char trailing = line.charAt(index);
            if (trailing != ' ' && trailing != '\t') {
                return false;
            }
        }
        return true;
    }

    private int leadingSpaces(String line) {
        int spaces = 0;
        while (spaces < line.length() && line.charAt(spaces) == ' ') {
            spaces++;
        }
        return spaces;
    }

    private List<String> splitLines(String content, List<String> path, int maxTokens, String separator) {
        return packAtoms(List.of(content.split("\\R", -1)), path, maxTokens, separator);
    }

    private List<String> packAtoms(List<String> atoms, List<String> path, int maxTokens, String separator) {
        return packWrappedAtoms(atoms, path, maxTokens, "", "", separator);
    }

    private List<String> packWrappedAtoms(List<String> atoms, List<String> path, int maxTokens,
                                          String prefix, String suffix, String separator) {
        List<String> chunks = new ArrayList<>();
        String current = "";
        for (String atom : atoms) {
            if (atom.isEmpty() && current.isEmpty()) {
                continue;
            }
            String joined = current.isEmpty() ? atom : current + separator + atom;
            if (fits(path, prefix + joined + suffix, maxTokens)) {
                current = joined;
                continue;
            }
            if (!current.isEmpty()) {
                chunks.add(prefix + current + suffix);
                current = "";
            }
            if (fits(path, prefix + atom + suffix, maxTokens)) {
                current = atom;
            } else {
                chunks.addAll(splitTokenSafe(atom, path, maxTokens, prefix, suffix));
            }
        }
        if (!current.isEmpty()) {
            chunks.add(prefix + current + suffix);
        }
        return chunks;
    }

    private List<String> splitTokenSafe(String content, List<String> path, int maxTokens) {
        return splitTokenSafe(content, path, maxTokens, "", "");
    }

    private List<String> splitTokenSafe(String content, List<String> path, int maxTokens, String prefix) {
        return splitTokenSafe(content, path, maxTokens, prefix, "");
    }

    private List<String> splitTokenSafe(String content, List<String> path, int maxTokens,
                                        String prefix, String suffix) {
        if (!fits(path, prefix + suffix, maxTokens)) {
            throw new IllegalArgumentException("Markdown title path and required container syntax exceed maxTokens");
        }
        List<String> chunks = new ArrayList<>();
        String remaining = content;
        while (!remaining.isEmpty()) {
            int acceptedEnd = -1;
            int codePoints = remaining.codePointCount(0, remaining.length());
            for (int prefixLength = 1; prefixLength <= codePoints; prefixLength++) {
                int end = remaining.offsetByCodePoints(0, prefixLength);
                if (fits(path, prefix + remaining.substring(0, end) + suffix, maxTokens)) {
                    acceptedEnd = end;
                }
            }
            if (acceptedEnd < 0) {
                throw new IllegalArgumentException(
                        "No non-empty prefix of the oversized Markdown semantic unit fits configured maxTokens");
            }
            chunks.add(prefix + remaining.substring(0, acceptedEnd) + suffix);
            remaining = remaining.substring(acceptedEnd);
        }
        return chunks;
    }

    private boolean fits(List<String> path, String content, int maxTokens) {
        return tokenCounter.count(MarkdownChunkPlanningStrategy.previewIndexText(path, content)) <= maxTokens;
    }

    private String endReason(SemanticUnit unit) {
        return String.valueOf(unit.attributes().getOrDefault("end", "PARAGRAPH_END"));
    }

    static SourceLocator sourceLocator(List<StructuredBlock> blocks) {
        List<String> blockIds = blocks.stream().map(StructuredBlock::blockId).toList();
        List<SourceLocator> locators = blocks.stream().map(StructuredBlock::sourceLocator)
                .filter(Objects::nonNull).toList();
        if (locators.isEmpty()) {
            return new SourceLocator("markdown", blockIds, null, null, null, null, null, null, List.of());
        }
        SourceLocator first = locators.get(0);
        SourceLocator last = locators.get(locators.size() - 1);
        return new SourceLocator(
                first.type(),
                blockIds,
                first.startOffset(),
                last.endOffset(),
                first.startLine(),
                last.endLine(),
                first.startPage(),
                last.endPage(),
                locators.stream().flatMap(locator -> locator.regions().stream()).toList());
    }

    record SplitPart(String content, SourceLocator sourceLocator, boolean forcedSplit, String endReason) {
    }

    private record Fence(char marker, int length) {
    }

    private record NormalizedText(String text, List<Integer> boundaries) {
    }
}
