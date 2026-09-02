package com.starsea.ai.parser;

import org.commonmark.node.Document;
import org.commonmark.node.Heading;
import org.commonmark.node.Node;
import org.commonmark.node.Text;
import org.commonmark.node.ThematicBreak;
import org.commonmark.parser.Parser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.reader.markdown.MarkdownDocumentReader;
import org.springframework.ai.reader.markdown.config.MarkdownDocumentReaderConfig;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 解析 {@code src/main/resources/file/科大百事通.md}，演示两层 MD AST 解析：
 *
 * <ol>
 *   <li><b>Spring AI MarkdownDocumentReader</b> —— 项目里 {@code PgVectorRagServiceImpl}
 *       用的就是这个，底层 commonmark-java，按 H1/H2/H3 标题与 {@code ---} 水平分隔线切分，
 *       输出 {@code List<org.springframework.ai.document.Document}。</li>
 *   <li><b>commonmark 直接解析</b> —— 不走 Spring AI 包装，直接拿完整的 Node 树，可任意遍历、
 *       抽取任意子节点（链接、代码块、自定义 frontmatter 等）。</li>
 * </ol>
 *
 * 工作目录：maven-surefire 默认从模块根（{@code server/Spring-AI}）启动，路径直接用相对路径即可。
 */
class MarkdownAstParserTest {

    private static final Path MD_PATH = Paths.get("src/main/resources/file/科大百事通.md");

    private Resource loadResource() {
        assertTrue(Files.exists(MD_PATH),
                "测试资源不存在: " + MD_PATH.toAbsolutePath());
        return new FileSystemResource(MD_PATH);
    }

    // ---------------------------------------------------------------------
    // 1) Spring AI 的 MarkdownDocumentReader（与 PgVectorRagServiceImpl 配置一致）
    // ---------------------------------------------------------------------
    @Test
    @DisplayName("[Spring AI Reader] 按 H3 + --- 切分成 Document 列表")
    void spring_ai_reader_splits_by_header() throws IOException {
        MarkdownDocumentReaderConfig config = MarkdownDocumentReaderConfig.builder()
                .withHorizontalRuleCreateDocument(true)
                .withIncludeCodeBlock(false)
                .withIncludeBlockquote(true)
                .build();
        MarkdownDocumentReader reader = new MarkdownDocumentReader(loadResource(), config);
        List<org.springframework.ai.document.Document> docs = reader.get();

        System.out.println("\n==== Spring AI MarkdownDocumentReader ====");
        System.out.println("docs.size = " + docs.size());
        System.out.println();
        for (int i = 0; i < docs.size(); i++) {
            org.springframework.ai.document.Document d = docs.get(i);
            Map<String, Object> meta = d.getMetadata();
            Object title = meta.get("title");
            String content = d.getText();
            String firstLine = content.lines().findFirst().orElse("");
            if (firstLine.length() > 80) firstLine = firstLine.substring(0, 80) + "...";
            System.out.printf("[%2d] title=%-30s len=%4d  首行: %s%n",
                    i, String.valueOf(title), content.length(), firstLine);
        }

        // 5 个 ### 章节（title 不带 ### 前缀，是纯文本）
        long sectionCount = docs.stream()
                .map(d -> d.getMetadata().get("title"))
                .filter(Objects::nonNull)
                .map(Object::toString)
                .filter(t -> !t.isBlank())
                .count();
        assertEquals(5, sectionCount, "应识别出 5 个 ### 章节");

        // 关键 Q/A 内容应保留
        assertTrue(docs.stream().anyMatch(d -> d.getText().contains("Q1")),  "应包含 Q1");
        assertTrue(docs.stream().anyMatch(d -> d.getText().contains("几本")), "应包含『几本』");
        assertEquals(5, docs.size(), "应切出 5 个 Document（5 个 H3 section）");
    }

    // ---------------------------------------------------------------------
    // 2) commonmark 直接解析：完整 AST 树形打印
    // ---------------------------------------------------------------------
    @Test
    @DisplayName("[commonmark] 完整 AST 树形结构")
    void commonmark_full_ast_dump() throws IOException {
        String md = Files.readString(MD_PATH, StandardCharsets.UTF_8);
        Parser parser = Parser.builder().build();
        Node root = parser.parse(md);

        System.out.println("\n==== CommonMark AST ====");
        printAst(root, 0);

        assertInstanceOf(Document.class, root, "根节点应为 commonmark Document");
    }

    // ---------------------------------------------------------------------
    // 3) commonmark 节点类型分布
    // ---------------------------------------------------------------------
    @Test
    @DisplayName("[commonmark] 节点类型分布统计")
    void commonmark_node_distribution() throws IOException {
        String md = Files.readString(MD_PATH, StandardCharsets.UTF_8);
        Parser parser = Parser.builder().build();
        Node root = parser.parse(md);

        Map<String, Integer> counts = new LinkedHashMap<>();
        walk(root, node -> counts.merge(node.getClass().getSimpleName(), 1, Integer::sum));

        System.out.println("\n==== 节点类型分布 ====");
        counts.entrySet().stream()
                .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
                .forEach(e -> System.out.printf("  %-22s %d%n", e.getKey(), e.getValue()));

        // 简单回归断言
        int headings = counts.getOrDefault("Heading", 0);
        int rules    = counts.getOrDefault("ThematicBreak", 0);
        int strongs  = counts.getOrDefault("StrongEmphasis", 0);

        System.out.println();
        System.out.println("  -> H3 sections   : " + headings);
        System.out.println("  -> --- rules     : " + rules);
        System.out.println("  -> **bold** runs : " + strongs);

        assertTrue(headings >= 5, "至少 5 个 Heading（H3）");
        assertTrue(rules    >= 4, "至少 4 个 ThematicBreak（---）");
        assertTrue(strongs  >= 15, "至少 15 个 StrongEmphasis（Q1..Q15 + A 段加粗）");
    }

    // ---------------------------------------------------------------------
    // 工具方法
    // ---------------------------------------------------------------------
    private void printAst(Node node, int depth) {
        if (depth > 8) {  // 防止极深
            System.out.println("  ".repeat(depth) + "...");
            return;
        }
        String name = node.getClass().getSimpleName();
        String detail = "";
        if (node instanceof Heading h) {
            detail = " level=" + h.getLevel();
        } else if (node instanceof Text t) {
            String lit = t.getLiteral().replaceAll("\\s+", " ").trim();
            if (lit.length() > 50) lit = lit.substring(0, 50) + "...";
            detail = lit.isEmpty() ? "" : "  \"" + lit + "\"";
        } else if (node instanceof ThematicBreak) {
            detail = "  (---)";
        }
        System.out.println("  ".repeat(depth) + name + detail);

        Node child = node.getFirstChild();
        while (child != null) {
            printAst(child, depth + 1);
            child = child.getNext();
        }
    }

    private void walk(Node node, Consumer<Node> visitor) {
        visitor.accept(node);
        Node child = node.getFirstChild();
        while (child != null) {
            walk(child, visitor);
            child = child.getNext();
        }
    }
}
