package com.starsea.ai.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.Set;

/** Report decisions are derived from explicit, frozen evidence; absent values never imply success. */
final class EvaluationRunPolicy {
    private EvaluationRunPolicy() {}

    static void apply(ObjectNode run, ObjectNode calibration) {
        ArrayNode reasons = run.putArray("verdictReasons");
        String phase = run.path("phase").asText();
        if (phase.equals("QUICK") || phase.equals("CALIBRATION")) {
            run.put("verdict", phase.equals("QUICK") ? "DIAGNOSTIC" : "CALIBRATION");
            return;
        }
        List<String> missing = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        if (!run.path("status").asText().equals("COMPLETED")) missing.add("评测未全部完成");
        if (!run.path("scope").asText().equals("ALL")) missing.add("正式验收需要完整语料快照");
        if (!run.path("datasetFrozen").asBoolean()) missing.add("问题集版本尚未冻结");
        if (run.path("modelResults").isEmpty()) missing.add("没有模型结果");
        Set<String> expectedModels=new HashSet<>();
        run.path("models").forEach(model->expectedModels.add(model.path("id").asText()));
        Set<String> actualModels=new HashSet<>();
        run.path("modelResults").forEach(model->actualModels.add(model.path("modelId").asText()));
        if(expectedModels.isEmpty() || !expectedModels.equals(actualModels) || actualModels.size()!=run.path("modelResults").size()) missing.add("模型结果与评测配置不一致");
        if (phase.equals("DELIVERY") && !run.path("retrievalMode").asText().equals("PRODUCTION")) missing.add("交付验收需要生产检索模式");
        JsonNode requirements = run.path("requirements");
        for (String field : List.of("minQuestions", "minUnanswerable", "hit5Min", "evidenceRetentionMin", "noAnswerFalsePositiveMax", "p95MaxMs"))
            if (!requirements.path(field).isNumber()) missing.add("未设置验收要求：" + field);
        if (calibration == null || !calibration.path("phase").asText().equals("CALIBRATION")
                || !calibration.path("status").asText().equals("COMPLETED")) missing.add("缺少完整的阈值校准运行");
        else if (!run.path("snapshotHash").equals(calibration.path("snapshotHash"))
                || !run.path("calibrationHash").equals(calibration.path("calibrationHash"))) missing.add("校准语料或问题版本与当前验收不一致");
        for (JsonNode result : run.path("modelResults")) {
            String model = result.path("modelId").asText();
            JsonNode configuration = find(run.path("models"), "id", model);
            String name = configuration == null ? model : configuration.path("displayName").asText(model);
            JsonNode metrics = result.path("metrics");
            if (!result.path("status").asText().equals("COMPLETED") || !result.path("identityStable").asBoolean()) missing.add(name + " 模型运行或版本验证不完整");
            int expectedChunks=run.path("chunkCount").asInt();
            if(expectedChunks<1 || result.path("totalChunks").asInt()!=expectedChunks || result.path("preparedChunks").asInt()!=expectedChunks) missing.add(name + " 完整语料的向量尚未全部准备");
            if(result.path("digest").asText().isBlank() || result.path("dimensions").asInt()<1 || result.path("selfSimilarity").asDouble(-1)<.999) missing.add(name + " 缺少有效模型身份或编码自检");
            Set<String> expectedQuestions=new HashSet<>(); run.path("questions").forEach(q->expectedQuestions.add(q.path("id").asText()));
            Set<String> completedQuestions=new HashSet<>();
            result.path("queries").forEach(q->{ if(q.path("status").asText().equals("COMPLETED")) completedQuestions.add(q.path("questionId").asText()); });
            if(expectedQuestions.isEmpty() || !expectedQuestions.equals(completedQuestions) || result.path("queries").size()!=expectedQuestions.size()
                    || metrics.path("questionCount").asInt()!=expectedQuestions.size() || metrics.path("completedCount").asInt()!=expectedQuestions.size()
                    || metrics.path("failedCount").asInt(-1)!=0) missing.add(name + " 问题执行结果不完整或统计数量不一致");
            if (!run.path("thresholds").path(model).isNumber()) missing.add(name + " 尚未固定阈值");
            if (metrics.path("unknownScoringCount").asInt(-1) != 0) missing.add(name + " 用于计分的结果尚有未知标签");
            if (metrics.path("reviewedCount").asInt(-1) != metrics.path("questionCount").asInt()) missing.add(name + " 问题尚未全部审核");
            if (metrics.path("questionCount").asInt() < requirements.path("minQuestions").asInt(1)) missing.add(name + " 验收问题数量不足");
            if (metrics.path("unanswerableCount").asInt() < requirements.path("minUnanswerable").asInt(1)) missing.add(name + " 无答案样本不足");
            if (metrics.path("denominators").path("hardNegativeWinRate").asInt() < requirements.path("minHardNegativeQuestions").asInt(1)) missing.add(name + " 困难负例样本不足");
            if (metrics.path("variantEligibleGroupCount").asInt() < requirements.path("minVariantGroups").asInt(1)) missing.add(name + " 同义问法意图组不足");
            if (calibration != null && !sameCalibrationModel(run, calibration, model)) missing.add(name + " 模型配置与校准运行不一致");
            if (calibration != null) {
                JsonNode calibrated=find(calibration.path("modelResults"),"modelId",model);
                if(calibrated==null || !calibrated.path("status").asText().equals("COMPLETED") || !calibrated.path("identityStable").asBoolean()
                        || calibrated.path("metrics").path("unknownScoringCount").asInt(-1)!=0
                        || calibrated.path("metrics").path("reviewedCount").asInt(-1)!=calibrated.path("metrics").path("questionCount").asInt()
                        || calibrated.path("metrics").path("answerableCount").asInt()<1 || calibrated.path("metrics").path("unanswerableCount").asInt()<1)
                    missing.add(name + " 校准运行缺少完整标注或正负问题样本");
            }
            compare(metrics, "hit5", requirements.path("hit5Min"), true, name, missing, failed);
            compare(metrics, "evidenceRetentionRate", requirements.path("evidenceRetentionMin"), true, name, missing, failed);
            compare(metrics, "noAnswerFalsePositiveRate", requirements.path("noAnswerFalsePositiveMax"), false, name, missing, failed);
            compare(metrics, "p95Ms", requirements.path("p95MaxMs"), false, name, missing, failed);
            if (requirements.has("hardNegativeWinMin")) compare(metrics,"hardNegativeWinRate",requirements.get("hardNegativeWinMin"),true,name,missing,failed);
            if (requirements.has("variantGroupHitMin")) compare(metrics,"variantGroupHitRate",requirements.get("variantGroupHitMin"),true,name,missing,failed);
            for (JsonNode critical : requirements.path("criticalQuestionIds")) {
                JsonNode query = find(result.path("queries"), "questionId", critical.asText());
                if (query == null) missing.add(name + " 缺少关键问题 " + critical.asText());
                else {
                    JsonNode definition=find(run.path("questions"),"id",critical.asText());
                    if(definition==null) { missing.add(name + " 缺少关键问题定义"); continue; }
                    boolean answerable=definition.path("answerable").asBoolean();
                    JsonNode value=query.path("metrics").path(answerable?"evidenceRetention":"noAnswerFalsePositive");
                    if(!value.isNumber()) missing.add(name + " 关键问题缺少有效指标：" + critical.asText());
                    else if(value.asDouble()!=(answerable?1:0)) failed.add(name + " 关键问题未达到要求：" + critical.asText());
                }
            }
        }
        missing.stream().distinct().forEach(reasons::add);
        failed.stream().distinct().forEach(reasons::add);
        run.put("verdict", !missing.isEmpty() ? "INSUFFICIENT" : failed.isEmpty() ? "PASS" : "FAIL");
    }

    private static boolean sameCalibrationModel(JsonNode run, JsonNode calibration, String id) {
        JsonNode current = run.path("modelFingerprints").path(id);
        return current.isTextual() && current.equals(calibration.path("modelFingerprints").path(id));
    }

    private static void compare(JsonNode metrics, String field, JsonNode expected, boolean minimum, String model,
                                List<String> missing, List<String> failed) {
        if (!metrics.path(field).isNumber()) { missing.add(model + " 缺少有效指标 " + field); return; }
        if (!expected.isNumber()) return;
        double actual = metrics.path(field).asDouble();
        if (minimum ? actual < expected.asDouble() : actual > expected.asDouble()) failed.add(model + " · " + metricTitle(field) + " 未达到验收要求");
    }

    private static String metricTitle(String field) {
        return switch (field) {
            case "hit5" -> "Hit@5";
            case "evidenceRetentionRate" -> "正确证据保留率";
            case "noAnswerFalsePositiveRate" -> "无答案误召回率";
            case "hardNegativeWinRate" -> "困难负例胜出率";
            case "variantGroupHitRate" -> "变体组全部命中率";
            case "p95Ms" -> "p95 延迟";
            default -> field;
        };
    }

    static JsonNode find(JsonNode list, String key, String value) {
        for (JsonNode item : list) if (item.path(key).asText().equals(value)) return item;
        return null;
    }
}
