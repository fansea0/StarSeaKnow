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

        if (title.isEmpty()) {
            return context.isEmpty() ? safeBody : context + "\n\n" + safeBody;
        }
        if (context.isEmpty()) {
            return title + "\n\n" + safeBody;
        }
        return title + "\n" + context + "\n\n" + safeBody;
    }

    public String title(List<String> sectionPath) {
        return sectionPath == null || sectionPath.isEmpty()
                ? ""
                : "标题：" + String.join(" > ", sectionPath);
    }
}
