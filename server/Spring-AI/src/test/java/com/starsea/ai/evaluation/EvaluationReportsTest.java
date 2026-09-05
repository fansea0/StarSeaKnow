package com.starsea.ai.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class EvaluationReportsTest {
    private final ObjectMapper json = new ObjectMapper();
    private final EvaluationReports reports = new EvaluationReports(json);

    @Test void htmlEscapesEveryUserControlledTextAndRemainsStandalone() throws Exception {
        ObjectNode run = fixture();
        String attack = "</pre><script>alert('x')</script><img src=x onerror=alert(1)> & \"quoted\"";
        run.put("id", attack);
        object(run, "/questions/0").put("query", attack).put("intentGroup", attack);
        object(run, "/models/0").put("displayName", attack);
        object(run, "/snapshot/chunks/0").put("content", attack).put("indexContent", attack);
        object(run, "/modelResults/1/queries/3").put("error", attack);
        run.putArray("verdictReasons").add(attack);
        String html = reports.html(run);
        assertTrue(html.startsWith("<!DOCTYPE html>"));
        assertTrue(html.contains("lang=\"zh-CN\""));
        assertTrue(html.contains("&lt;script&gt;"));
        assertTrue(html.contains("&amp; &quot;quoted&quot;"));
        assertFalse(Pattern.compile("<(script|img|iframe|link)\\b", Pattern.CASE_INSENSITIVE).matcher(html).find());
        assertFalse(html.contains(attack));
        assertTrue(html.contains("@media print"));
        assertTrue(html.contains("#11243B"));
        assertTrue(html.contains("#00A6A6"));
    }

    @Test void missingMetricsAndScoresNeverBecomeZeroOrImplicitSuccess() throws Exception {
        ObjectNode run = fixture();
        run.put("status", "FAILED").remove("verdict");
        for (JsonNode result : run.path("modelResults")) {
            ObjectNode metrics = ((ObjectNode) result).putObject("metrics");
            metrics.putNull("hit1").put("hit5", 0);
            metrics.putObject("denominators").put("hit1", 0).put("hit5", 2);
        }
        String html = reports.html(run);
        assertTrue(html.contains("FAILED"));
        assertFalse(html.contains("PASS"));
        assertTrue(metricRow(html, "hit1").contains("—"));
        assertFalse(metricRow(html, "hit1").contains("0.00%"));
        assertTrue(metricRow(html, "hit5").contains("0.00%"));
        assertTrue(metricRow(html, "hit5").contains("适用分母：2"));
        assertTrue(metricRow(html, "p95Ms").contains("适用分母：未知"));
        Map<String, String> row = rows(reports.csv(run)).stream()
                .filter(r -> r.get("modelId").equals("candidate") && r.get("questionId").equals("q2")
                        && r.get("chunkId").equals("c1")).findFirst().orElseThrow();
        assertEquals("", row.get("score"));
        assertEquals("", row.get("rank"));
        assertTrue(json.readTree(reports.manifest(run)).path("verdict").isNull());
    }

    @Test void exportsPreserveProvidedVerdictAndActualScopePhaseAndStatus() throws Exception {
        ObjectNode run = fixture().put("scope", "SELECTED").put("phase", "QUICK")
                .put("status", "PARTIAL").put("verdict", "INSUFFICIENT");
        String html = reports.html(run);
        for (String expected : List.of("SELECTED", "QUICK", "PARTIAL", "INSUFFICIENT", "模型版本未核实")) {
            assertTrue(html.contains(expected), expected);
        }
        assertFalse(html.contains("PASS"));
        assertEquals("INSUFFICIENT", json.readTree(reports.manifest(run)).path("verdict").asText());
        assertTrue(rows(reports.csv(run)).stream().allMatch(r -> r.get("verdict").equals("INSUFFICIENT")));
        // The exporter does not replace an explicit upstream verdict with its own policy.
        run.put("verdict", "DIAGNOSTIC");
        assertTrue(reports.html(run).contains("DIAGNOSTIC"));
    }

    @Test void htmlIncludesModelIdentityCalibrationBaselineAndCompleteCaseEvidence() throws Exception {
        String html = reports.html(fixture());
        for (String evidence : List.of("候选模型", "sha256:base", "sha256:candidate", "Q8_0", "768",
                "query: ", "document: ", "ollama-0.11", "snapshot-hash", "dataset-hash",
                "2026-09-04T10:00:00Z", "Apple M4", "efSearch", "criticalQuestionIds",
                "同义问法", "原问题", "退化", "困难负例", "无答案", "提升证据不足",
                "0.5500", "0.4500", "0.5200", "正文完整第一段\n正文完整第二段", "完整入模标题\n正文完整第一段",
                "手册.pdf", "权限 / 配置", "适用分母：2", "未进入候选", "超时失败")) {
            assertTrue(html.contains(evidence), "Missing evidence: " + evidence);
        }
        assertTrue(html.contains("score &gt; threshold"));
        assertTrue(html.contains("95%"));
    }

    @Test void manifestPreservesFrozenReproductionDataAndRecursivelyRemovesSecretsWithoutMutation() throws Exception {
        ObjectNode run = fixture();
        run.put("API_KEY", "root-sensitive");
        object(run, "/models/0").put("apiKey", "model-sensitive");
        object(run, "/environment").putObject("nested").put("accessToken", "nested-sensitive")
                .put("PASSWORD", "password-sensitive").put("client_secret", "secret-sensitive").put("threads", 4);
        object(run, "/modelResults/0").putArray("extensions").addObject().put("refresh-token", "array-sensitive");
        ObjectNode before = run.deepCopy();
        JsonNode manifest = json.readTree(reports.manifest(run));
        for (String key : List.of("snapshot", "questions", "thresholds", "requirements", "retrievalSettings",
                "verdict", "verdictReasons", "datasetHash", "modelFingerprints", "calibrationRunId")) {
            assertEquals(run.get(key), manifest.get(key), key);
        }
        assertEquals("sha256:base", manifest.at("/models/0/digest").asText());
        assertEquals(4, manifest.at("/environment/nested/threads").asInt());
        assertEquals(-0.2, manifest.at("/modelResults/1/queries/3/hits/0/score").asDouble());
        for (String exported : List.of(reports.manifest(run), reports.html(run), reports.csv(run))) {
            assertFalse(exported.contains("-sensitive"));
        }
        assertFalse(manifest.has("API_KEY"));
        assertFalse(manifest.at("/models/0").has("apiKey"));
        assertEquals(before, run);
        assertEquals(reports.manifest(run), reports.manifest(run));
    }

    @Test void csvEscapesFormulasQuotesAndMultilineTextButKeepsSignedNumericScores() throws Exception {
        for (String formula : List.of("=1+1", "+SUM(1,2)", "-2+3", "@SUM(A1)", "\t=1", "\r=1", "\n=1",
                "  =1", "\uFEFF=1", "\u0000=1", "\uFF1D1+1")) {
            ObjectNode run = fixture();
            object(run, "/questions/0").put("query", formula);
            object(run, "/models/1").put("displayName", formula);
            object(run, "/snapshot/chunks/2").put("content", formula);
            List<Map<String, String>> csv = rows(reports.csv(run));
            Map<String, String> first = csv.stream().filter(r -> r.get("questionId").equals("q1")).findFirst().orElseThrow();
            assertEquals("'" + formula, first.get("query"));
            assertTrue(csv.stream().filter(r -> r.get("modelId").equals("candidate"))
                    .allMatch(r -> r.get("modelName").equals("'" + formula)));
            Map<String, String> negative = csv.stream().filter(r -> r.get("modelId").equals("candidate")
                    && r.get("questionId").equals("q4") && r.get("chunkId").equals("c3")).findFirst().orElseThrow();
            assertEquals("-0.2", negative.get("score"));
            assertEquals("'" + formula, negative.get("content"));
        }
        ObjectNode run = fixture();
        String text = "中文, \"quoted\"\r\n第二行\n第三行";
        object(run, "/questions/0").put("query", text);
        assertEquals(text, rows(reports.csv(run)).get(0).get("query"));
    }

    @Test void csvRetainsEveryHitJudgedUnreturnedEmptyAndFailureIncludingModelsWithoutQueries() throws Exception {
        List<Map<String, String>> csv = rows(reports.csv(fixture()));
        Map<String, String> judged = csv.stream().filter(r -> r.get("modelId").equals("candidate")
                && r.get("questionId").equals("q1") && r.get("chunkId").equals("c1")).findFirst().orElseThrow();
        assertEquals("JUDGED_UNRETURNED", judged.get("rowType"));
        assertEquals("0.52", judged.get("score"));
        assertEquals("", judged.get("rank"));
        assertEquals("1", judged.get("baselineRank"));
        assertTrue(csv.stream().anyMatch(r -> r.get("questionId").equals("q3") && r.get("rowType").equals("NO_ANSWER")));
        assertTrue(csv.stream().anyMatch(r -> r.get("questionId").equals("q4")
                && r.get("rowType").equals("QUERY_FAILURE") && r.get("error").contains("超时失败")));
        assertTrue(csv.stream().anyMatch(r -> r.get("modelId").equals("broken") && r.get("questionId").equals("q1")
                && r.get("rowType").equals("MODEL_FAILURE") && r.get("error").contains("模型不可达")));
        assertFalse(csv.stream().anyMatch(r -> r.get("modelId").equals("candidate")
                && r.get("questionId").equals("q4") && r.get("rowType").equals("NO_ANSWER")));
        assertTrue(csv.stream().anyMatch(r -> r.get("modelId").equals("candidate") && r.get("questionId").equals("q4")
                && r.get("chunkId").equals("c3") && r.get("queryStatus").equals("FAILED")));
    }

    @Test void absentModelResultsStillExportUnrunQuestionsAndNeverManufactureCompletedState() throws Exception {
        ObjectNode run = fixture().put("status", "QUEUED");
        run.remove("modelResults");
        run.remove("verdict");
        List<Map<String, String>> csv = rows(reports.csv(run));
        assertEquals(12, csv.size());
        assertTrue(csv.stream().allMatch(r -> r.get("rowType").equals("NOT_RUN")));
        assertTrue(csv.stream().allMatch(r -> r.get("score").isEmpty()));
        assertFalse(reports.html(run).contains("PASS"));
        assertEquals(3, json.readTree(reports.manifest(run)).path("models").size());
    }

    @Test void productionEvidenceRetainsExactReferenceAndIdentifiesSupplementaryRankOrigin() throws Exception {
        ObjectNode run = fixture().put("phase", "DELIVERY").put("retrievalMode", "PRODUCTION");
        ObjectNode query = object(run, "/modelResults/1/queries/0");
        query.put("exactReferenceSearchMs", 7).putObject("exactReferenceMetrics").put("hit5", 1);
        object(run, "/modelResults/1/queries/0/judgedScores/0").put("rank", 11);
        String html = reports.html(run);
        assertTrue(html.contains("exactReferenceMetrics"));
        assertTrue(html.contains("exactReferenceSearchMs"));
        List<Map<String, String>> csv = rows(reports.csv(run));
        Map<String, String> reference = csv.stream().filter(r -> r.get("modelId").equals("candidate")
                && r.get("questionId").equals("q1") && r.get("chunkId").equals("c1")).findFirst().orElseThrow();
        assertEquals("11", reference.get("rank"));
        assertEquals("JUDGED_REFERENCE", reference.get("rankSource"));
        assertEquals("", reference.get("rankDelta"), "Reference and retrieved ranks are not comparable in production mode");
    }

    @Test void hardNegativeWithoutAScoreOrLabelRemainsUnknownEvidence() throws Exception {
        ObjectNode run = fixture();
        object(run, "/questions/0").withArray("hardNegativeIds").add("unscored-negative");
        Map<String, String> negative = rows(reports.csv(run)).stream()
                .filter(r -> r.get("modelId").equals("candidate") && r.get("questionId").equals("q1")
                        && r.get("chunkId").equals("unscored-negative")).findFirst().orElseThrow();
        assertEquals("true", negative.get("hardNegative"));
        assertEquals("", negative.get("label"));
        assertEquals("", negative.get("score"));
        assertEquals("UNRETURNED", negative.get("rowType"));
    }

    private ObjectNode fixture() throws Exception {
        return (ObjectNode) json.readTree("""
                {
                  "id":"run-1","knowledgeId":17,"datasetId":"ds-1","datasetRevision":3,"datasetFrozen":true,
                  "datasetHash":"dataset-hash","snapshotId":"snap-1","snapshotHash":"snapshot-hash",
                  "scope":"ALL","phase":"ACCEPTANCE","retrievalMode":"EXACT","status":"PARTIAL",
                  "createdAt":"2026-09-04T10:00:00Z","finishedAt":"2026-09-04T10:01:00Z",
                  "topK":5,"baselineModelId":"base","thresholds":{"base":0.55,"candidate":0.45},
                  "verdict":"INSUFFICIENT","verdictReasons":["模型版本未核实"],"calibrationRunId":"cal-1",
                  "modelFingerprints":{"base":"fingerprint-1","candidate":"fingerprint-2"},
                  "environment":{"hardware":"Apple M4","javaVersion":"17"},
                  "retrievalSettings":{"thresholdOperator":">","efSearch":100,"candidateLimit":10},
                  "requirements":{"hit5Min":0.9,"minQuestions":30,"criticalQuestionIds":["q2"]},
                  "snapshot":{"hash":"snapshot-hash","scope":"ALL","chunks":[
                    {"id":"c1","fileId":1,"fileName":"手册.pdf","sectionPath":["权限","配置"],
                     "content":"正文完整第一段\\n正文完整第二段","indexContent":"完整入模标题\\n正文完整第一段","contentHash":"chunk-hash-1"},
                    {"id":"c2","fileId":1,"fileName":"手册.pdf","content":"困难负例原文","indexContent":"困难负例入模文本"},
                    {"id":"c3","fileId":2,"fileName":"附录.pdf","content":"负分原文","indexContent":"负分入模文本"}
                  ]},
                  "questions":[
                    {"id":"q1","query":"原问题","intentGroup":"权限组","category":"original","split":"ACCEPTANCE","answerable":true,"reviewed":true,"labels":{"c1":2,"c2":0},"hardNegativeIds":["c2"]},
                    {"id":"q2","query":"同义问法","intentGroup":"权限组","category":"paraphrase","split":"ACCEPTANCE","answerable":true,"reviewed":true,"labels":{"c1":2},"hardNegativeIds":[]},
                    {"id":"q3","query":"无答案问题","intentGroup":"空组","category":"negative","split":"ACCEPTANCE","answerable":false,"reviewed":true,"labels":{},"hardNegativeIds":[]},
                    {"id":"q4","query":"失败问题","intentGroup":"失败组","category":"negative","split":"ACCEPTANCE","answerable":false,"reviewed":true,"labels":{},"hardNegativeIds":[]}
                  ],
                  "models":[
                    {"id":"base","displayName":"当前基线","modelName":"embed-base","digest":"sha256:base","dimensions":768,"revision":1,"quantization":"Q8_0","queryPrefix":"query: ","documentPrefix":"document: ","ollamaVersion":"ollama-0.11","baseUrl":"http://localhost:11434"},
                    {"id":"candidate","displayName":"候选模型","modelName":"embed-candidate","digest":"sha256:candidate","dimensions":1024},
                    {"id":"broken","displayName":"失败模型","modelName":"embed-broken"}
                  ],
                  "modelResults":[
                    {"modelId":"base","status":"COMPLETED","preparedChunks":3,"totalChunks":3,"buildMs":80,
                     "metrics":{"questionCount":4,"completedCount":4,"failedCount":0,"hit1":1,"hit5":1,"p95Ms":12,"denominators":{"hit1":2,"hit5":2,"p95Ms":4}},
                     "queries":[
                       {"questionId":"q1","status":"COMPLETED","hits":[{"chunkId":"c1","rank":1,"score":0.56},{"chunkId":"c2","rank":2,"score":0.54}],"judgedScores":[],"metrics":{"hit5":1,"firstAnswerRank":1}},
                       {"questionId":"q2","status":"COMPLETED","hits":[{"chunkId":"c1","rank":1,"score":0.6}],"metrics":{"hit5":1,"firstAnswerRank":1}},
                       {"questionId":"q3","status":"COMPLETED","hits":[],"metrics":{"returnedCount":0}},
                       {"questionId":"q4","status":"COMPLETED","hits":[],"metrics":{"returnedCount":0}}
                     ]},
                    {"modelId":"candidate","status":"PARTIAL","preparedChunks":3,"totalChunks":3,
                     "metrics":{"questionCount":4,"completedCount":3,"failedCount":1,"hit5":0,"hardNegativeWinRate":0,"variantGroupHitRate":0,"denominators":{"hit5":2,"hardNegativeWinRate":1,"variantGroupHitRate":1}},
                     "calibration":[{"threshold":0.45,"evidenceRetentionRate":0.5,"noAnswerFalsePositiveRate":0,"answerableEmptyRate":0.5,"denominators":{"evidenceRetentionRate":2,"noAnswerFalsePositiveRate":1,"answerableEmptyRate":2}}],
                     "comparison":{"pairedQuestionCount":3,"intentGroupCount":2,"bootstrapSeed":42,"metrics":{"hit5":{"baseline":1,"candidate":0,"delta":-1,"lower95":-1,"upper95":0.1,"evidence":"INCONCLUSIVE","pairedQuestionCount":2,"intentGroupCount":1}}},
                     "queries":[
                       {"questionId":"q1","status":"COMPLETED","hits":[{"chunkId":"c2","rank":1,"score":0.54}],"judgedScores":[{"chunkId":"c1","score":0.52}],"metrics":{"hit5":0,"hardNegativeWin":0,"gap":-0.02}},
                       {"questionId":"q2","status":"COMPLETED","hits":[],"metrics":{"hit5":0,"returnedCount":0}},
                       {"questionId":"q3","status":"COMPLETED","hits":[],"metrics":{"returnedCount":0}},
                       {"questionId":"q4","status":"FAILED","error":"超时失败","hits":[{"chunkId":"c3","rank":1,"score":-0.2}],"metrics":{}}
                     ]},
                    {"modelId":"broken","status":"FAILED","error":"模型不可达","queries":[]}
                  ]
                }
                """);
    }

    private static ObjectNode object(ObjectNode node, String pointer) { return (ObjectNode) node.at(pointer); }

    private static String metricRow(String html, String key) {
        var matcher = Pattern.compile("<tr data-metric=\"" + key + "\">(.*?)</tr>", Pattern.DOTALL).matcher(html);
        assertTrue(matcher.find(), key);
        return matcher.group(1);
    }

    /** Parse RFC 4180 records so quoted newlines are not mistaken for additional evidence rows. */
    private static List<Map<String, String>> rows(String csv) {
        List<List<String>> records = new ArrayList<>();
        List<String> record = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = csv.startsWith("\uFEFF") ? 1 : 0; i < csv.length(); i++) {
            char c = csv.charAt(i);
            if (c == '"') {
                if (quoted && i + 1 < csv.length() && csv.charAt(i + 1) == '"') { cell.append('"'); i++; }
                else quoted = !quoted;
            } else if (c == ',' && !quoted) { record.add(cell.toString()); cell.setLength(0); }
            else if (c == '\r' && !quoted) {
                assertTrue(i + 1 < csv.length() && csv.charAt(++i) == '\n');
                record.add(cell.toString()); cell.setLength(0); records.add(record); record = new ArrayList<>();
            } else cell.append(c);
        }
        assertFalse(quoted);
        assertTrue(record.isEmpty());
        assertFalse(records.isEmpty(), "CSV must contain a header record");
        List<String> headers = records.remove(0);
        List<Map<String, String>> result = new ArrayList<>();
        for (List<String> values : records) {
            assertEquals(headers.size(), values.size());
            Map<String, String> row = new LinkedHashMap<>();
            for (int i = 0; i < headers.size(); i++) row.put(headers.get(i), values.get(i));
            result.add(row);
        }
        return result;
    }
}
