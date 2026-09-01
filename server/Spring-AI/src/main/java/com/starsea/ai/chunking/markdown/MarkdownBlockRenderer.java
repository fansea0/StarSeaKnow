package com.starsea.ai.chunking.markdown;

import com.starsea.ai.chunking.model.SourceLocator;
import org.commonmark.Extension;
import org.commonmark.node.Node;
import org.commonmark.node.SourceSpan;
import org.commonmark.renderer.text.TextContentRenderer;

import java.util.ArrayList;
import java.util.List;

/** Renders Markdown blocks from their CommonMark source spans without normalizing markup. */
final class MarkdownBlockRenderer {

    private final String source;
    private final List<Integer> lineOffsets;
    private final TextContentRenderer textRenderer;

    MarkdownBlockRenderer(String source, List<Extension> extensions) {
        this.source = source;
        this.lineOffsets = lineOffsets(source);
        this.textRenderer = TextContentRenderer.builder().extensions(extensions).build();
    }

    String plainText(Node node) {
        return withoutTerminalLineEnding(textRenderer.render(node));
    }

    private String withoutTerminalLineEnding(String rendered) {
        if (rendered.endsWith("\r\n")) {
            return rendered.substring(0, rendered.length() - 2);
        }
        if (rendered.endsWith("\n") || rendered.endsWith("\r")) {
            return rendered.substring(0, rendered.length() - 1);
        }
        return rendered;
    }

    String rawText(Node node) {
        SourceBounds bounds = sourceBounds(node);
        return source.substring(bounds.startOffset(), bounds.endOffset());
    }

    SourceLocator sourceLocator(String blockId, Node node) {
        SourceBounds bounds = sourceBounds(node);
        return new SourceLocator(
                "markdown",
                List.of(blockId),
                bounds.startOffset(),
                bounds.endOffset(),
                bounds.startLine(),
                bounds.endLine(),
                null,
                null,
                List.of());
    }

    private SourceBounds sourceBounds(Node node) {
        List<SourceSpan> spans = node.getSourceSpans();
        if (spans.isEmpty()) {
            throw new IllegalArgumentException("Markdown block has no source spans: " + node.getClass().getSimpleName());
        }

        SourceSpan first = spans.get(0);
        SourceSpan last = spans.get(spans.size() - 1);
        int startOffset = offsetAt(first);
        int endOffset = offsetAt(last) + last.getLength();
        return new SourceBounds(startOffset, endOffset, first.getLineIndex() + 1, last.getLineIndex() + 1);
    }

    private int offsetAt(SourceSpan span) {
        return lineOffsets.get(span.getLineIndex()) + span.getColumnIndex();
    }

    private static List<Integer> lineOffsets(String source) {
        List<Integer> offsets = new ArrayList<>();
        offsets.add(0);
        for (int index = 0; index < source.length(); index++) {
            if (source.charAt(index) == '\n') {
                offsets.add(index + 1);
            }
        }
        return List.copyOf(offsets);
    }

    private record SourceBounds(int startOffset, int endOffset, int startLine, int endLine) {
    }
}
