package com.starsea.ai.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EvaluationRunPolicyTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test void incompleteEvidenceCannotPassEvenWithPerfectScores() {
        ObjectNode run = formal();
        run.withArray("modelResults").addObject().put("modelId", "m").put("status", "COMPLETED")
                .putObject("metrics").put("hit5",1).put("unknownScoringCount",1);
        EvaluationRunPolicy.apply(run, null);
        assertEquals("INSUFFICIENT", run.path("verdict").asText());
        assertFalse(run.path("verdictReasons").isEmpty());
    }

    @Test void subsetNeverBecomesProductionEvidence() {
        ObjectNode run = formal().put("scope", "SELECTED");
        EvaluationRunPolicy.apply(run, null);
        assertEquals("INSUFFICIENT", run.path("verdict").asText());
        assertTrue(run.path("verdictReasons").toString().contains("完整语料"));
    }

    @Test void quickComparisonIsAlwaysLabeledDiagnostic() {
        ObjectNode run = formal().put("phase", "QUICK");
        EvaluationRunPolicy.apply(run, null);
        assertEquals("DIAGNOSTIC", run.path("verdict").asText());
    }

    @Test void incompleteBuildAndContradictoryQuestionCountsCannotPass() {
        ObjectNode run=complete();
        ObjectNode calibration=calibration(run);
        EvaluationRunPolicy.apply(run,calibration);
        assertEquals("PASS",run.path("verdict").asText(),run.path("verdictReasons").toString());
        ((ObjectNode)run.path("modelResults").get(0)).put("preparedChunks",1);
        EvaluationRunPolicy.apply(run,calibration);
        assertEquals("INSUFFICIENT",run.path("verdict").asText());
    }

    @Test void criticalNoAnswerQuestionPassesWhenCorrectlyRejected() {
        ObjectNode run=complete();
        run.with("requirements").putArray("criticalQuestionIds").add("q3");
        EvaluationRunPolicy.apply(run,calibration(run));
        assertEquals("PASS",run.path("verdict").asText(),run.path("verdictReasons").toString());
    }

    @Test void calibrationCurveMustContainTheSubmittedThreshold() {
        ObjectNode run = complete();
        run.putObject("thresholds").put("m", .51);
        EvaluationRunPolicy.apply(run, calibration(run));
        assertEquals("INSUFFICIENT", run.path("verdict").asText());
        assertTrue(run.path("verdictReasons").toString().contains("阈值"));
    }

    private ObjectNode calibration(ObjectNode run) {
        ObjectNode result = (ObjectNode) run.path("modelResults").get(0);
        result.putArray("calibration").addObject().put("threshold", .5);
        return run.deepCopy().put("phase","CALIBRATION");
    }

    private ObjectNode complete() {
        ObjectNode run=formal().put("snapshotHash","hash").put("calibrationHash","calhash").put("chunkCount",100);
        run.putArray("models").addObject().put("id","m");
        run.putObject("modelFingerprints").put("m","fingerprint");
        run.putObject("thresholds").put("m",.5);
        run.putObject("requirements").put("minQuestions",3).put("minUnanswerable",1).put("hit5Min",.9)
                .put("evidenceRetentionMin",.9).put("noAnswerFalsePositiveMax",.1).put("p95MaxMs",1000);
        var questions=run.putArray("questions");
        questions.addObject().put("id","q1").put("answerable",true);
        questions.addObject().put("id","q2").put("answerable",true);
        questions.addObject().put("id","q3").put("answerable",false);
        ObjectNode result=run.putArray("modelResults").addObject().put("modelId","m").put("status","COMPLETED")
                .put("identityStable",true).put("digest","digest").put("dimensions",768).put("selfSimilarity",1)
                .put("preparedChunks",100).put("totalChunks",100);
        var queries=result.putArray("queries");
        for(String id:new String[]{"q1","q2","q3"}) {
            var query=queries.addObject().put("questionId",id).put("status","COMPLETED");
            query.putObject("metrics").put("evidenceRetention",1).put("noAnswerFalsePositive",0);
        }
        result.putObject("metrics").put("questionCount",3).put("completedCount",3).put("failedCount",0)
                .put("reviewedCount",3).put("answerableCount",2).put("unanswerableCount",1).put("unknownScoringCount",0)
                .put("variantEligibleGroupCount",1).put("hit5",1).put("evidenceRetentionRate",1)
                .put("noAnswerFalsePositiveRate",0).put("p95Ms",10).putObject("denominators").put("hardNegativeWinRate",1);
        return run;
    }

    private ObjectNode formal() {
        return json.createObjectNode().put("phase","ACCEPTANCE").put("scope","ALL")
                .put("status","COMPLETED").put("datasetFrozen", true);
    }
}
