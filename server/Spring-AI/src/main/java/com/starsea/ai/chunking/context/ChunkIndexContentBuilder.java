package com.starsea.ai.chunking.context;

import org.springframework.stereotype.Component;

import java.util.List;

/** Formats the text supplied to indexing without changing the persisted chunk body. */
@Component
public final class ChunkIndexContentBuilder {

    public String build(List<String> sectionPath, String overlap, String body) {
        String safeBody = body == null ? "" : body;
        String title = title(sectionPath);
        String context = overlap == null || overlap.isBlank() ? "" : "上文：" + overlap;

        if (context.isEmpty()) {
            return preview(sectionPath, safeBody);
        }
        if (title.isEmpty()) {
            return context + "\n\n" + safeBody;
        }
        return title + "\n" + context + "\n\n" + safeBody;
    }

    /** Common pre-vectorization boundary: title path and persisted body, without overlap. */
    public static String preview(List<String> sectionPath, String body) {
        String safeBody = body == null ? "" : body;
        String title = titleText(sectionPath);
        return title.isEmpty() ? safeBody : title + "\n\n" + safeBody;
    }

    public String title(List<String> sectionPath) {
        return titleText(sectionPath);
    }

    private static String titleText(List<String> sectionPath) {
        return sectionPath == null || sectionPath.isEmpty()
                ? ""
                : "标题：" + String.join(" > ", sectionPath);
    }
}
