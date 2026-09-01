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

        SourceLocator locator = sourceLocator(unit.blocks());
        return parts.stream().filter(part -> !part.isBlank())
                .map(part -> new SplitPart(part, locator, true,
                        structuralBlock.type() == BlockType.PARAGRAPH ? "SENTENCE_END" : "CONTAINER_END"))
                .toList();
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
        if (lines.length < 2 || !(lines[0].startsWith("```") || lines[0].startsWith("~~~"))) {
            return splitLines(content, path, maxTokens, "\n");
        }
        String opening = lines[0];
        String closing = lines[lines.length - 1].startsWith("```") || lines[lines.length - 1].startsWith("~~~")
                ? lines[lines.length - 1] : opening.substring(0, 3);
        List<String> bodyLines = new ArrayList<>();
        int end = lines[lines.length - 1].equals(closing) ? lines.length - 1 : lines.length;
        for (int index = 1; index < end; index++) {
            bodyLines.add(lines[index]);
        }
        String prefix = opening + "\n";
        String suffix = "\n" + closing;
        return packWrappedAtoms(bodyLines, path, maxTokens, prefix, suffix, "\n");
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
            int low = 1;
            int high = remaining.codePointCount(0, remaining.length());
            int accepted = 0;
            while (low <= high) {
                int middle = (low + high) >>> 1;
                int end = remaining.offsetByCodePoints(0, middle);
                if (fits(path, prefix + remaining.substring(0, end) + suffix, maxTokens)) {
                    accepted = middle;
                    low = middle + 1;
                } else {
                    high = middle - 1;
                }
            }
            if (accepted == 0) {
                throw new IllegalArgumentException("A single Markdown code point cannot fit maxTokens");
            }
            int end = remaining.offsetByCodePoints(0, accepted);
            chunks.add(prefix + remaining.substring(0, end) + suffix);
            remaining = remaining.substring(end);
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
}
