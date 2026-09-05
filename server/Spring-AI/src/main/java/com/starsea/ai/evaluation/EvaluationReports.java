package com.starsea.ai.evaluation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Read-only exports of a complete frozen run; no service calls, re-scoring or verdict policy. */
@Component
public class EvaluationReports {
    private static final JsonNode MISSING = MissingNode.getInstance();
    private static final List<String> METRICS = List.of("hit1", "hit5", "hitK", "mrr10", "ndcg5",
            "hardNegativeWinRate", "variantGroupHitRate", "noAnswerFalsePositiveRate",
            "evidenceRetentionRate", "answerableEmptyRate", "failureRate", "p50Ms", "p95Ms");
    private static final List<String> COUNTS = List.of("questionCount", "completedCount", "failedCount",
            "answerableCount", "unanswerableCount", "reviewedCount", "unknownTopKCount", "unknownScoringCount");
    private static final Map<String, String> LABELS = Map.ofEntries(
            Map.entry("hit1", "Hit@1"), Map.entry("hit5", "Hit@5"), Map.entry("hitK", "Hit@K"),
            Map.entry("mrr10", "MRR@10"), Map.entry("ndcg5", "nDCG@5"),
            Map.entry("hardNegativeWinRate", "困难负例胜出率"), Map.entry("variantGroupHitRate", "变体组全部命中率"),
            Map.entry("noAnswerFalsePositiveRate", "无答案误召回率"), Map.entry("evidenceRetentionRate", "正确证据保留率"),
            Map.entry("answerableEmptyRate", "有答案返回空比例"), Map.entry("failureRate", "失败率"),
            Map.entry("p50Ms", "p50 延迟（ms）"), Map.entry("p95Ms", "p95 延迟（ms）"),
            Map.entry("questionCount", "问题总数"), Map.entry("completedCount", "完成数"), Map.entry("failedCount", "失败数"),
            Map.entry("answerableCount", "可回答数"), Map.entry("unanswerableCount", "无答案数"),
            Map.entry("reviewedCount", "审核数"), Map.entry("unknownTopKCount", "Top K 未知标签数"),
            Map.entry("unknownScoringCount", "计分范围未知标签数"));
    private static final String[] CSV_HEADERS = {"runId", "scope", "phase", "retrievalMode", "runStatus", "verdict",
            "modelId", "modelName", "modelStatus", "questionId", "query", "intentGroup", "category", "split",
            "answerable", "reviewed", "queryStatus", "rowType", "chunkId", "rank", "score", "label", "hardNegative",
            "inHits", "returnedCount", "baselineRank", "baselineScore", "rankDelta", "scoreDelta", "fileName",
            "sectionPath", "content", "indexContent", "embeddingMs", "searchMs", "topK", "threshold", "error", "rankSource"};

    private final ObjectMapper json;

    public EvaluationReports(ObjectMapper json) { this.json = Objects.requireNonNull(json, "json"); }

    /** JSON retains all frozen fields, including future extensions, after recursive credential removal. */
    public String manifest(ObjectNode run) {
        ObjectNode clean = clean(run);
        for (String field : List.of("id", "knowledgeId", "scope", "phase", "status", "verdict", "verdictReasons",
                "createdAt", "finishedAt", "datasetId", "datasetRevision", "datasetHash", "datasetFrozen",
                "snapshotId", "snapshotHash", "snapshot", "questions", "models", "modelResults", "modelFingerprints",
                "baselineModelId", "calibrationRunId", "calibrationHash", "topK", "thresholds", "requirements",
                "environment", "retrievalMode", "retrievalSettings")) {
            if (!clean.has(field)) clean.putNull(field);
        }
        ObjectNode export = clean.putObject("reportExport");
        export.put("format", "starsea.embedding-evaluation").put("version", 1)
                .put("source", "frozen-run").put("verdictSource", "provided-by-run")
                .put("missingValues", "null means unavailable; absent thresholds disable filtering")
                .put("redaction", "recursive field names containing password/token/secret/apiKey, ignoring case and separators");
        return pretty(clean);
    }

    public String html(ObjectNode run) {
        ObjectNode clean = clean(run);
        List<Model> models = models(clean);
        StringBuilder out = new StringBuilder(32768).append(HTML_START);
        out.append("<header><p class=\"eyebrow\">STARSEA KNOW · RETRIEVAL EVALUATION</p><h1>向量检索评测报告</h1>")
                .append("<p>运行 <code>").append(escape(value(clean.path("id")))).append("</code></p><div class=\"badges\">");
        badge(out, "实际范围", clean.path("scope"));
        badge(out, "评测阶段", clean.path("phase"));
        badge(out, "运行状态", clean.path("status"));
        badge(out, "检索模式", clean.path("retrievalMode"));
        out.append("</div><div class=\"verdict\">提供的结论：<strong>")
                .append(escape(describe(clean.path("verdict")))).append("</strong></div>");
        block(out, "判定原因", clean.path("verdictReasons"));
        error(out, clean.path("error"));
        out.append("<p class=\"note\">结论直接取自冻结运行；导出器不推断验收通过。失败、未运行、未知标签与缺失指标均保留。</p></header><main>");
        coverage(out, clean);
        summary(out, clean, models);
        configurations(out, clean, models);
        cases(out, clean, models);
        out.append("<section><h2>复现元数据与验收协议</h2>");
        ObjectNode metadata = clean.deepCopy();
        metadata.remove(List.of("snapshot", "models", "modelResults", "questions"));
        block(out, "完整运行参数、时间、环境与要求", metadata);
        block(out, "精确检索与交付链路差异", clean.path("deliveryComparison"));
        block(out, "未覆盖的业务范围", clean.path("uncoveredScope"));
        out.append("<p class=\"note\">EXACT 表示精确检索基准，PRODUCTION 表示记录的生产链路；两者应分别复测。")
                .append("未提供的链路对照或业务覆盖信息为未知。报告结论仅适用于本次数据和配置，生成回答质量需另行验收。</p></section>")
                .append("</main><footer>StarSeaKnow · 冻结运行报告 · v1</footer></body></html>");
        return out.toString();
    }

    public String csv(ObjectNode run) {
        ObjectNode clean = clean(run);
        List<Model> models = models(clean);
        Map<String, JsonNode> chunks = index(clean.path("snapshot").path("chunks"), "id");
        Map<String, JsonNode> questions = questions(clean, models);
        StringBuilder out = new StringBuilder("\uFEFF");
        csvLine(out, (Object[]) CSV_HEADERS);
        for (Model model : models) {
            if (questions.isEmpty()) {
                csvRow(out, clean, model, MISSING, MISSING, null, MISSING,
                        failed(model.result) ? "MODEL_FAILURE" : "NOT_RUN");
            }
            for (JsonNode question : questions.values()) {
                JsonNode query = model.queries.getOrDefault(question.path("id").asText(), MISSING);
                JsonNode baseline = baselineQuery(clean, models, question);
                if (query.isMissingNode()) {
                    csvRow(out, clean, model, question, query, null, baseline,
                            failed(model.result) ? "MODEL_FAILURE" : "NOT_RUN");
                    continue;
                }
                List<Evidence> evidence = evidence(question, query, chunks);
                if (failed(query)) csvRow(out, clean, model, question, query, null, baseline, "QUERY_FAILURE");
                else if (!completed(query)) csvRow(out, clean, model, question, query, null, baseline, "NOT_RUN");
                else if (emptyResponse(query)) csvRow(out, clean, model, question, query, null, baseline,
                        question.path("answerable").isBoolean() && !question.path("answerable").booleanValue() ? "NO_ANSWER" : "EMPTY");
                else if (evidence.isEmpty()) csvRow(out, clean, model, question, query, null, baseline, "UNKNOWN");
                for (Evidence item : evidence) csvRow(out, clean, model, question, query, item, baseline, item.kind);
            }
        }
        if (models.isEmpty()) csvRow(out, clean, new Model(MISSING, MISSING), MISSING, MISSING, null, MISSING,
                failed(clean) ? "RUN_FAILURE" : "NOT_RUN");
        return out.toString();
    }

    private void coverage(StringBuilder out, JsonNode run) {
        out.append("<section><h2>业务范围与样本覆盖</h2><div class=\"cards\">");
        Map<String, JsonNode> chunks = index(run.path("snapshot").path("chunks"), "id");
        Set<String> documents = new LinkedHashSet<>(), intents = new LinkedHashSet<>();
        Map<String, Integer> categories = new LinkedHashMap<>();
        int reviewed = 0, known = 0, unknownReview = 0;
        for (JsonNode chunk : chunks.values()) {
            JsonNode id = first(chunk.path("fileId"), chunk.path("fileName"));
            if (present(id)) documents.add(value(id));
        }
        for (JsonNode question : run.path("questions")) {
            if (present(question.path("intentGroup"))) intents.add(value(question.path("intentGroup")));
            categories.merge(value(question.path("category")), 1, Integer::sum);
            if (question.path("reviewed").isBoolean()) reviewed += question.path("reviewed").booleanValue() ? 1 : 0;
            else unknownReview++;
            for (JsonNode label : question.path("labels")) if (label.isIntegralNumber() && label.asInt() >= 0 && label.asInt() <= 2) known++;
        }
        card(out, "冻结 chunk", run.path("snapshot").path("chunks").isArray() ? String.valueOf(chunks.size()) : "未知");
        card(out, "已识别文档", run.path("snapshot").path("chunks").isArray() ? String.valueOf(documents.size()) : "未知");
        card(out, "冻结问题", run.path("questions").isArray() ? String.valueOf(run.path("questions").size()) : "未知");
        card(out, "已识别意图组", run.path("questions").isArray() ? String.valueOf(intents.size()) : "未知");
        out.append("</div><p>已审核问题：").append(run.path("questions").isArray() ? reviewed : "未知")
                .append("；审核状态未知：").append(run.path("questions").isArray() ? unknownReview : "未知")
                .append("；显式 0/1/2 标注：").append(run.path("questions").isArray() ? known : "未知").append("。</p>");
        block(out, "类别分布", json.valueToTree(categories));
        block(out, "业务范围", first(run.path("businessScope"), run.path("requirements").path("businessScope")));
        out.append("<p class=\"note\">SELECTED 为候选小集合，不能外推全库效果。未标注不等于无关；标注覆盖以每个模型的计分范围未知数为准。</p></section>");
    }

    private void summary(StringBuilder out, JsonNode run, List<Model> models) {
        out.append("<section><h2>模型指标对比</h2><p>基线模型：<code>")
                .append(escape(value(run.path("baselineModelId")))).append("</code> · Top K：")
                .append(escape(value(run.path("topK")))).append("</p><div class=\"table-wrap\"><table><thead><tr><th>指标</th>");
        for (Model model : models) out.append("<th>").append(escape(model.name())).append("<br><small>")
                .append(escape(describe(model.result.path("status")))).append("</small></th>");
        out.append("</tr></thead><tbody>");
        for (String count : COUNTS) {
            out.append("<tr><th>").append(LABELS.get(count)).append("</th>");
            for (Model model : models) cell(out, model.result.path("metrics").path(count));
            out.append("</tr>");
        }
        for (String metric : METRICS) {
            out.append("<tr data-metric=\"").append(metric).append("\"><th>").append(LABELS.get(metric)).append("</th>");
            for (Model model : models) metricCell(out, metric, model.result.path("metrics"));
            out.append("</tr>");
        }
        out.append("</tbody></table></div><p class=\"note\">— 表示缺失或不适用，数值 0 保留。适用分母直接取自计算结果；")
                .append("未提供分母时显示未知。失败数单列，成功子集的指标不代表整次运行通过。MRR / nDCG 保留原始比例；不跨模型比较绝对余弦阈值。</p></section>");
    }

    private void configurations(StringBuilder out, JsonNode run, List<Model> models) {
        out.append("<section><h2>模型身份、独立阈值与基线差异</h2><p>阈值边界：<code>")
                .append(escape("score " + value(first(run.path("retrievalSettings").path("thresholdOperator"), json.getNodeFactory().textNode(">"))) + " threshold"))
                .append("</code>；未配置阈值表示关闭过滤，数值 0 仍是有效阈值。</p>");
        for (Model model : models) {
            out.append("<article class=\"model\"><h3>").append(escape(model.name())).append(" · <code>")
                    .append(escape(model.id())).append("</code></h3>");
            block(out, "冻结模型身份与编码配置", model.identity);
            ObjectNode preparation = model.result.isObject() ? model.result.deepCopy() : json.createObjectNode();
            preparation.remove(List.of("queries", "metrics", "calibration", "comparison"));
            for (String key : List.of("status", "dimensions", "digest", "identityStable", "selfSimilarity", "preparedChunks", "totalChunks", "buildMs", "error"))
                if (!preparation.has(key)) preparation.putNull(key);
            block(out, "准备进度、完整性与失败", preparation);
            out.append("<p>本模型冻结阈值：<strong>").append(escape(threshold(run.path("thresholds").path(model.id())))).append("</strong></p>");
            out.append("<h4>校准权衡</h4><table><thead><tr><th>阈值</th><th>正确证据保留率</th><th>无答案误召回率</th><th>有答案返回空比例</th></tr></thead><tbody>");
            for (JsonNode point : model.result.path("calibration")) {
                out.append("<tr>"); cell(out, threshold(point.path("threshold")));
                for (String metric : List.of("evidenceRetentionRate", "noAnswerFalsePositiveRate", "answerableEmptyRate")) metricCell(out, metric, point);
                out.append("</tr>");
            }
            out.append("</tbody></table>");
            if (model.result.path("calibration").isEmpty()) out.append("<p class=\"note\">未提供校准曲线。</p>");
            block(out, "校准样本与失败记录", model.result.path("calibration"));
            comparison(out, model.result.path("comparison"));
            block(out, "按意图组汇总的指标", model.result.path("metrics").path("intentGroupMetrics"));
            block(out, "意图组指标适用分母", model.result.path("metrics").path("intentGroupDenominators"));
            out.append("</article>");
        }
        out.append("</section>");
    }

    private void comparison(StringBuilder out, JsonNode comparison) {
        out.append("<h4>相较基线的配对差异</h4><table><thead><tr><th>指标</th><th>基线</th><th>候选</th><th>候选 − 基线</th><th>95% 区间</th><th>问题 / 意图组</th><th>差异证据</th></tr></thead><tbody>");
        comparison.path("metrics").fields().forEachRemaining(entry -> {
            JsonNode metric = entry.getValue();
            out.append("<tr>"); cell(out, LABELS.getOrDefault(entry.getKey(), entry.getKey()));
            for (String key : List.of("baseline", "candidate", "delta")) cell(out, decimal(metric.path(key)));
            cell(out, "[" + decimal(metric.path("lower95")) + ", " + decimal(metric.path("upper95")) + "]");
            cell(out, value(metric.path("pairedQuestionCount")) + " / " + value(metric.path("intentGroupCount")));
            cell(out, describe(metric.path("evidence"))); out.append("</tr>");
        });
        out.append("</tbody></table><p class=\"note\">区间跨零时提升证据不足；模型差异和交付门槛分别判断。</p>");
        block(out, "配对范围与重采样参数", comparison);
    }

    private void cases(StringBuilder out, JsonNode run, List<Model> models) {
        Map<String, JsonNode> chunks = index(run.path("snapshot").path("chunks"), "id");
        out.append("<section><h2>逐题证据 · 同义问法与困难负例</h2><p class=\"note\">按冻结意图组识别原句与变体；")
                .append("同一 chunk 横向对齐名次。未进入候选不等于零分，补充标注分数与原始候选分开呈现。")
                .append("分数保留四位显示，排序使用运行记录中的完整精度及原始名次。差值只解释已标注候选范围。</p>");
        for (JsonNode question : questions(run, models).values()) {
            out.append("<article class=\"question\"><h3>").append(escape(value(question.path("query")))).append("</h3>");
            block(out, "问题 ID、意图组、类别、分组与冻结标注", question);
            JsonNode baseline = baselineQuery(run, models, question);
            Map<String, Map<String, Evidence>> evidenceByModel = new LinkedHashMap<>();
            Map<String, Evidence> aligned = new LinkedHashMap<>();
            out.append("<table><thead><tr><th>模型 / 状态</th><th>Hit@K / Hit@5</th><th>首个正例名次</th><th>正负例差值</th><th>基线退化与负例证据</th></tr></thead><tbody>");
            for (Model model : models) {
                JsonNode query = model.queries.getOrDefault(question.path("id").asText(), MISSING);
                Map<String, Evidence> evidence = new LinkedHashMap<>();
                for (Evidence item : evidence(question, query, chunks)) { evidence.put(item.id, item); aligned.putIfAbsent(item.id, item); }
                evidenceByModel.put(model.id(), evidence);
                JsonNode metrics = query.path("metrics");
                out.append("<tr>"); cell(out, model.name() + " · " + describe(query.path("status")));
                cell(out, value(metrics.path("hitK")) + " / " + value(metrics.path("hit5")));
                cell(out, metrics.path("firstAnswerRank")); cell(out, decimal(metrics.path("gap")));
                List<String> observations = new ArrayList<>();
                if (failed(query) || (query.isMissingNode() && failed(model.result))) observations.add("失败，不能作为成功或正确拒答");
                if (completed(query) && completed(baseline) && !model.id().equals(run.path("baselineModelId").asText())) {
                    BigDecimal delta = difference(first(metrics.path("hitK"), metrics.path("hit5")),
                            first(baseline.path("metrics").path("hitK"), baseline.path("metrics").path("hit5")));
                    if (delta != null && delta.signum() < 0) observations.add("相较基线退化");
                }
                if (isZero(metrics.path("hardNegativeWin"))) observations.add("困难负例未被严格击败（含同分）");
                if (metrics.path("hardNegativeTie").asBoolean(false)) observations.add("同分候选");
                if (question.path("answerable").isBoolean() && !question.path("answerable").booleanValue()
                        && finite(metrics.path("noAnswerFalsePositive")) && metrics.path("noAnswerFalsePositive").asDouble() > 0)
                    observations.add("无答案仍返回");
                cell(out, observations.isEmpty() ? "—" : String.join("；", observations)); out.append("</tr>");
            }
            out.append("</tbody></table>");
            for (Model model : models) {
                JsonNode query = model.queries.getOrDefault(question.path("id").asText(), MISSING);
                error(out, first(query.path("error"), model.result.path("error")));
                ObjectNode detail = query.isObject() ? query.deepCopy() : json.createObjectNode();
                detail.remove(List.of("hits", "judgedScores"));
                for (String key : List.of("status", "embeddingMs", "searchMs", "metrics"))
                    if (!detail.has(key)) detail.putNull(key);
                if (query.path("exactReferenceMetrics").isObject()) {
                    out.append("<h4>").append(escape(model.name())).append(" · 精确参考与本次检索</h4>")
                            .append("<table><thead><tr><th>逐题指标</th><th>精确参考</th><th>本次检索</th><th>本次 − 精确</th></tr></thead><tbody>");
                    for (String key : List.of("hit1", "hit5", "hitK", "mrr10", "ndcg5", "evidenceRetention", "noAnswerFalsePositive", "answerableEmpty")) {
                        JsonNode reference = query.path("exactReferenceMetrics").path(key), actual = query.path("metrics").path(key);
                        out.append("<tr>"); cell(out, LABELS.getOrDefault(key, key)); cell(out, decimal(reference)); cell(out, decimal(actual));
                        cell(out, deltaText(difference(actual, reference))); out.append("</tr>");
                    }
                    out.append("</tbody></table>");
                }
                block(out, model.name() + " · 逐题指标与耗时", detail);
            }
            if (aligned.isEmpty()) out.append("<p class=\"note\">没有候选证据；请结合逐题状态判断空返回、未运行或失败。</p>");
            for (Evidence item : aligned.values()) {
                out.append("<div class=\"evidence\"><h4>Chunk <code>").append(escape(item.id)).append("</code> · ")
                        .append(escape(value(item.source("fileName")))).append("</h4><p>来源：")
                        .append(escape(sectionPath(item.source("sectionPath")))).append(" · 相关性：")
                        .append(escape(value(item.label))).append(" · 困难负例：").append(escape(value(item.hardNegative))).append("</p>")
                        .append("<table><thead><tr><th>模型</th><th>记录名次</th><th>原始余弦</th><th>候选归属</th><th>名次变化 / 分数变化（相对基线）</th></tr></thead><tbody>");
                JsonNode baseHit = hit(baseline, item.id);
                for (Model model : models) {
                    Evidence current = evidenceByModel.get(model.id()).get(item.id);
                    JsonNode scored = current == null ? MISSING : current.hit;
                    out.append("<tr>"); cell(out, model.name()); cell(out, scored.path("rank")); cell(out, decimal(scored.path("score")));
                    cell(out, current == null ? "未记录" : current.inHits ? "检索候选" : "未进入候选（标注证据；名次为补充评分参考）");
                    cell(out, deltaText(rankDifference(run, current, baseline)) + " / "
                            + deltaText(difference(scored.path("score"), baseHit.path("score")))); out.append("</tr>");
                }
                out.append("</tbody></table><h5>完整来源正文</h5><pre class=\"source\">")
                        .append(escape(value(item.source("content")))).append("</pre><h5>完整入模文本</h5><pre class=\"source\">")
                        .append(escape(value(item.source("indexContent")))).append("</pre>");
                block(out, "来源版本与定位", item.chunk.isMissingNode() ? MISSING : withoutText(item.chunk));
                out.append("</div>");
            }
            out.append("</article>");
        }
        out.append("</section>");
    }

    private JsonNode withoutText(JsonNode source) {
        ObjectNode metadata = source.deepCopy();
        metadata.remove(List.of("content", "indexContent"));
        return metadata;
    }

    private void csvRow(StringBuilder out, JsonNode run, Model model, JsonNode question, JsonNode query,
                        Evidence evidence, JsonNode baseline, String kind) {
        JsonNode scored = evidence == null ? MISSING : evidence.hit;
        JsonNode baseHit = evidence == null ? MISSING : hit(baseline, evidence.id);
        csvLine(out, run.path("id"), run.path("scope"), run.path("phase"), run.path("retrievalMode"),
                run.path("status"), run.path("verdict"), model.id(), model.name(), model.result.path("status"),
                question.path("id"), first(question.path("query"), query.path("query")), question.path("intentGroup"),
                question.path("category"), question.path("split"), question.path("answerable"), question.path("reviewed"),
                query.path("status"), kind, evidence == null ? null : evidence.id, scored.path("rank"), scored.path("score"),
                evidence == null ? MISSING : evidence.label, evidence == null ? MISSING : evidence.hardNegative,
                evidence == null ? null : evidence.inHits, query.path("metrics").path("returnedCount"),
                baseHit.path("rank"), baseHit.path("score"), rankDifference(run, evidence, baseline),
                difference(scored.path("score"), baseHit.path("score")), evidence == null ? MISSING : evidence.source("fileName"),
                evidence == null ? null : sectionPath(evidence.source("sectionPath")),
                evidence == null ? MISSING : evidence.source("content"), evidence == null ? MISSING : evidence.source("indexContent"),
                query.path("embeddingMs"), query.path("searchMs"), run.path("topK"), run.path("thresholds").path(model.id()),
                first(query.path("error"), first(model.result.path("error"), run.path("error"))),
                evidence == null || !present(scored.path("rank")) ? null : evidence.inHits ? "RETRIEVAL" : "JUDGED_REFERENCE");
    }

    private static void csvLine(StringBuilder out, Object... values) {
        for (int i = 0; i < values.length; i++) {
            if (i > 0) out.append(',');
            Object value = values[i];
            boolean numeric = value instanceof Number;
            String cell;
            if (value instanceof JsonNode node) {
                numeric = finite(node);
                cell = !present(node) || (node.isNumber() && !numeric) ? "" : node.isValueNode() ? node.asText() : node.toString();
            } else cell = value == null ? "" : value.toString();
            if (!numeric && formula(cell)) cell = "'" + cell;
            out.append('"').append(cell.replace("\"", "\"\"")).append('"');
        }
        out.append("\r\n");
    }

    /** Test only a normalized copy; exported text itself is retained verbatim after the apostrophe. */
    private static boolean formula(String text) {
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC);
        for (int i = 0; i < normalized.length();) {
            int c = normalized.codePointAt(i);
            if (Character.isISOControl(c) || Character.getType(c) == Character.FORMAT) return true;
            if (!Character.isWhitespace(c) && !Character.isSpaceChar(c)) return c == '=' || c == '+' || c == '-' || c == '@';
            i += Character.charCount(c);
        }
        return false;
    }

    private List<Evidence> evidence(JsonNode question, JsonNode query, Map<String, JsonNode> chunks) {
        Map<String, JsonNode> ranked = index(query.path("hits"), "chunkId");
        Map<String, JsonNode> all = new LinkedHashMap<>(ranked);
        index(query.path("judgedScores"), "chunkId").forEach(all::putIfAbsent);
        question.path("labels").fieldNames().forEachRemaining(id -> all.putIfAbsent(id, MISSING));
        for (JsonNode id : question.path("hardNegativeIds")) all.putIfAbsent(id.asText(), MISSING);
        List<Evidence> result = new ArrayList<>();
        all.forEach((id, score) -> {
            boolean inHits = ranked.containsKey(id);
            JsonNode hardNegative = score.path("hardNegative");
            if (question.path("hardNegativeIds").isArray()) {
                boolean found = false;
                for (JsonNode negative : question.path("hardNegativeIds")) if (negative.asText().equals(id)) found = true;
                hardNegative = json.getNodeFactory().booleanNode(found);
            }
            result.add(new Evidence(id, score, chunks.getOrDefault(id, MISSING),
                    first(question.path("labels").path(id), score.path("label")), hardNegative, inHits,
                    inHits ? "HIT" : score.isMissingNode() ? "UNRETURNED" : "JUDGED_UNRETURNED"));
        });
        return result;
    }

    private static List<Model> models(JsonNode run) {
        Map<String, JsonNode> identities = index(run.path("models"), "id");
        Map<String, JsonNode> results = index(run.path("modelResults"), "modelId");
        Set<String> ids = new LinkedHashSet<>(identities.keySet()); ids.addAll(results.keySet());
        List<Model> models = new ArrayList<>();
        for (String id : ids) models.add(new Model(identities.getOrDefault(id, MISSING), results.getOrDefault(id, MISSING)));
        return models;
    }

    private Map<String, JsonNode> questions(JsonNode run, List<Model> models) {
        Map<String, JsonNode> questions = index(run.path("questions"), "id");
        for (Model model : models) model.queries.forEach((id, query) -> {
            ObjectNode fallback = json.createObjectNode().put("id", id);
            fallback.set("query", query.path("query").isMissingNode() ? json.nullNode() : query.path("query"));
            questions.putIfAbsent(id, fallback);
        });
        return questions;
    }

    private static JsonNode baselineQuery(JsonNode run, List<Model> models, JsonNode question) {
        for (Model model : models) if (model.id().equals(run.path("baselineModelId").asText()))
            return model.queries.getOrDefault(question.path("id").asText(), MISSING);
        return MISSING;
    }

    private static JsonNode hit(JsonNode query, String id) {
        for (String collection : List.of("hits", "judgedScores"))
            for (JsonNode hit : query.path(collection)) if (hit.path("chunkId").asText().equals(id)) return hit;
        return MISSING;
    }

    private static Map<String, JsonNode> index(JsonNode values, String key) {
        Map<String, JsonNode> result = new LinkedHashMap<>();
        for (JsonNode item : values) if (present(item.path(key))) result.put(item.path(key).asText(), item);
        return result;
    }

    private record Model(JsonNode identity, JsonNode result, Map<String, JsonNode> queries) {
        Model(JsonNode identity, JsonNode result) { this(identity, result, index(result.path("queries"), "questionId")); }
        String id() { return present(identity.path("id")) ? identity.path("id").asText() : result.path("modelId").asText(""); }
        String name() { return value(first(identity.path("displayName"), first(identity.path("modelName"), first(identity.path("id"), result.path("modelId"))))); }
    }

    private record Evidence(String id, JsonNode hit, JsonNode chunk, JsonNode label, JsonNode hardNegative, boolean inHits, String kind) {
        JsonNode source(String field) { return first(chunk.path(field), hit.path(field)); }
    }

    private ObjectNode clean(ObjectNode run) { return (ObjectNode) sanitize(Objects.requireNonNull(run, "run")); }

    private JsonNode sanitize(JsonNode node) {
        if (node.isObject()) {
            ObjectNode copy = json.createObjectNode();
            node.fields().forEachRemaining(entry -> {
                String name = Normalizer.normalize(entry.getKey(), Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
                if (!name.contains("password") && !name.contains("token") && !name.contains("secret") && !name.contains("apikey"))
                    copy.set(entry.getKey(), sanitize(entry.getValue()));
            });
            return copy;
        }
        if (node.isArray()) {
            ArrayNode copy = json.createArrayNode();
            node.forEach(value -> copy.add(sanitize(value)));
            return copy;
        }
        return node.deepCopy();
    }

    private String pretty(JsonNode node) {
        try { return json.writerWithDefaultPrettyPrinter().writeValueAsString(node); }
        catch (JsonProcessingException exception) { throw new IllegalArgumentException("Cannot serialize frozen evaluation report", exception); }
    }

    private void block(StringBuilder out, String heading, JsonNode node) {
        out.append("<div class=\"metadata\"><h4>").append(escape(heading)).append("</h4><pre>")
                .append(escape(present(node) ? node.isContainerNode() ? pretty(node) : value(node) : "— 未提供"))
                .append("</pre></div>");
    }

    private void error(StringBuilder out, JsonNode error) {
        if (present(error) && !error.asText().isBlank() || error.isContainerNode() && !error.isEmpty())
            out.append("<p class=\"error\">失败信息：").append(escape(error.isContainerNode() ? pretty(error) : value(error))).append("</p>");
    }

    private static void badge(StringBuilder out, String heading, JsonNode node) {
        out.append("<span class=\"badge\">").append(escape(heading)).append("：<b>").append(escape(describe(node))).append("</b></span>");
    }

    private static void card(StringBuilder out, String heading, String value) {
        out.append("<div class=\"card\"><small>").append(escape(heading)).append("</small><strong>")
                .append(escape(value)).append("</strong></div>");
    }

    private static void cell(StringBuilder out, Object value) {
        out.append("<td>").append(escape(value instanceof JsonNode node ? value(node) : Objects.toString(value, "—"))).append("</td>");
    }

    private static void metricCell(StringBuilder out, String key, JsonNode metrics) {
        JsonNode number = metrics.path(key), denominator = metrics.path("denominators").path(key);
        String display = !finite(number) ? "—" : key.endsWith("Ms") || key.equals("mrr10") || key.equals("ndcg5")
                ? decimal(number) : number.decimalValue().multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP).toPlainString() + "%";
        out.append("<td><span class=\"number\">").append(display).append("</span><small class=\"denominator\">适用分母：")
                .append(escape(present(denominator) ? value(denominator) : "未知")).append("</small></td>");
    }

    private static boolean present(JsonNode node) { return node != null && !node.isNull() && !node.isMissingNode(); }
    private static JsonNode first(JsonNode preferred, JsonNode fallback) { return present(preferred) ? preferred : fallback; }
    private static boolean finite(JsonNode node) { return node.isNumber() && Double.isFinite(node.doubleValue()); }
    private static boolean isZero(JsonNode node) { return finite(node) && node.decimalValue().signum() == 0; }
    private static boolean failed(JsonNode node) {
        String state = node.path("status").asText();
        JsonNode error = node.path("error");
        return state.equals("FAILED") || state.equals("CANCELLED") || present(error)
                && (error.isContainerNode() ? !error.isEmpty() : !error.asText().isBlank());
    }
    private static boolean completed(JsonNode node) { return node.path("status").asText().equals("COMPLETED") && !failed(node); }
    private static boolean emptyResponse(JsonNode query) {
        JsonNode returned = query.path("metrics").path("returnedCount");
        return finite(returned) ? isZero(returned) : query.path("hits").isArray() && query.path("hits").isEmpty();
    }
    private static String value(JsonNode node) {
        return !present(node) || node.isNumber() && !finite(node) ? "—" : node.isValueNode() ? node.asText() : node.toString();
    }
    private static String decimal(JsonNode node) { return finite(node) ? node.decimalValue().setScale(4, RoundingMode.HALF_UP).toPlainString() : "—"; }
    private static String threshold(JsonNode node) { return present(node) ? decimal(node) : "关闭过滤（未配置）"; }
    private static BigDecimal difference(JsonNode candidate, JsonNode baseline) {
        return finite(candidate) && finite(baseline) ? candidate.decimalValue().subtract(baseline.decimalValue()) : null;
    }
    private static BigDecimal rankDifference(JsonNode run, Evidence candidate, JsonNode baseline) {
        if (candidate == null) return null;
        boolean baselineInHits = false;
        for (JsonNode hit : baseline.path("hits")) if (hit.path("chunkId").asText().equals(candidate.id)) baselineInHits = true;
        // Production candidates and supplementary exact-reference ranks describe different searches.
        if (run.path("retrievalMode").asText().equals("PRODUCTION") && candidate.inHits != baselineInHits) return null;
        return difference(candidate.hit.path("rank"), hit(baseline, candidate.id).path("rank"));
    }
    private static String deltaText(BigDecimal delta) { return delta == null ? "—" : delta.setScale(4, RoundingMode.HALF_UP).toPlainString(); }
    private static String sectionPath(JsonNode path) {
        if (!path.isArray()) return value(path);
        List<String> parts = new ArrayList<>(); path.forEach(part -> parts.add(value(part)));
        return String.join(" / ", parts);
    }
    private static String describe(JsonNode node) {
        String raw = value(node);
        String label = switch (raw) {
            case "ALL" -> "全部语料"; case "SELECTED" -> "候选小集合";
            case "QUICK" -> "快速诊断"; case "CALIBRATION" -> "阈值校准"; case "ACCEPTANCE" -> "冻结验收";
            case "DELIVERY" -> "生产链路复测"; case "EXACT" -> "精确检索"; case "PRODUCTION" -> "生产检索";
            case "QUEUED", "PENDING" -> "等待运行"; case "RUNNING" -> "运行中"; case "COMPLETED" -> "已完成";
            case "PARTIAL" -> "部分完成"; case "FAILED" -> "失败"; case "CANCELLED" -> "已取消";
            case "DIAGNOSTIC" -> "诊断结果"; case "PASS" -> "满足已设门槛"; case "FAIL" -> "不满足";
            case "INSUFFICIENT" -> "证据不足"; case "INCONCLUSIVE" -> "提升证据不足";
            case "IMPROVED" -> "观察到提升"; case "REGRESSED" -> "观察到退化";
            default -> "";
        };
        return label.isEmpty() ? raw : label + " · " + raw;
    }
    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static final String HTML_START = """
            <!DOCTYPE html>
            <html lang="zh-CN"><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width, initial-scale=1">
            <meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'">
            <title>StarSeaKnow · 向量检索评测报告</title><style>
            :root{color-scheme:light;--ink:#11243B;--wash:#EAF1F5;--paper:#FCFDFC;--teal:#00A6A6;--muted:#64748B}
            *{box-sizing:border-box}body{margin:0;background:var(--wash);color:var(--ink);font:14px/1.7 "Noto Sans SC","PingFang SC","Microsoft YaHei",sans-serif}
            header,main,footer{max-width:1180px;margin:auto}header{padding:48px 40px 32px;border-top:8px solid var(--teal);background:var(--ink);color:var(--paper)}
            .eyebrow{font-size:11px;letter-spacing:.2em;color:#9CDDDD}h1,h2{font-family:"Noto Serif SC","Songti SC",serif}h1{font-size:34px;line-height:1.25;margin:12px 0 24px}
            h2{font-size:24px;border-left:4px solid var(--teal);padding-left:12px;margin:0 0 20px}h3{font-size:18px;margin:20px 0 12px}h4,h5{margin:16px 0 6px}
            main{padding:24px 0}section{background:var(--paper);padding:32px 40px;margin-bottom:24px;border:1px solid #DAE4E9;border-radius:10px}
            .badges{display:flex;flex-wrap:wrap;gap:10px}.badge{padding:6px 12px;border:1px solid #678294;border-radius:5px;font-size:12px}.badge b{font-weight:600}
            .verdict{font-size:20px;margin:24px 0 12px}.verdict strong{color:#9CE6DC}.cards{display:grid;grid-template-columns:repeat(4,1fr);gap:16px}
            .card{padding:16px;background:var(--wash);border-radius:6px}.card strong{display:block;font:28px/1.5 "JetBrains Mono",monospace}.card small,.note{color:var(--muted)}
            header .note{color:#BDD0DD}header pre{background:#20374F;color:var(--paper)}.note{font-size:12px}.table-wrap{overflow-x:auto}
            table{width:100%;border-collapse:collapse;font-size:12px;table-layout:fixed}th,td{border-bottom:1px solid #DAE4E9;text-align:left;padding:10px 8px;vertical-align:top;overflow-wrap:anywhere}
            th{background:var(--wash);font-weight:600}thead th{border-top:2px solid var(--teal)}.number,code{font-family:"JetBrains Mono",monospace}.number{font-size:15px}
            .denominator{display:block;color:var(--muted);font-size:10px}.model,.question{border-top:1px solid #CCD9E1;padding-top:8px;margin-top:28px}
            .evidence{border-left:3px solid var(--teal);padding:0 0 0 16px;margin:24px 0}pre{font:12px/1.7 "JetBrains Mono","Noto Sans SC",monospace;white-space:pre-wrap;overflow-wrap:anywhere;word-break:break-word;background:#F1F5F7;padding:12px;border-radius:5px;margin:6px 0}
            pre.source{font:13px/1.8 "Noto Sans SC","PingFang SC",sans-serif;background:#F2F9F8}.error{color:#8B2438;border-left:3px solid #BD6573;background:#FCEFF1;padding:10px;white-space:pre-wrap;overflow-wrap:anywhere}
            footer{padding:8px 40px 32px;color:var(--muted);font-size:12px}p,h3{overflow-wrap:anywhere}
            @media(max-width:700px){header,section{padding:24px 16px}.cards{grid-template-columns:repeat(2,1fr)}h1{font-size:28px}main{padding:12px 0}.table-wrap table{min-width:620px}}
            @page{size:A4;margin:14mm}
            @media print{body{background:white;font-size:10pt}header{background:white;color:#11243B;padding:12px 0}header .note,.eyebrow{color:#64748B}.verdict strong{color:#11243B}header pre{background:#F1F5F7;color:#11243B}
            main{padding:0}section{border:0;border-radius:0;margin:0;padding:20px 0}h1{font-size:24pt}h2{font-size:17pt}h2,h3,h4,h5{break-after:avoid}tr,.card{break-inside:avoid}thead{display:table-header-group}
            table{font-size:8pt}.table-wrap{overflow:visible}.table-wrap table{min-width:0}pre{font-size:8pt}.source{overflow:visible}footer{padding:12px 0}.badges{gap:5px}*{-webkit-print-color-adjust:exact;print-color-adjust:exact}}
            </style></head><body>
            """;
}
