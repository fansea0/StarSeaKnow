package com.starsea.ai.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class EvaluationDatasetValidationTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test void preservesUnknownAndRequiresReviewedPositiveEvidence() {
        ArrayNode cases = json.createArrayNode();
        ObjectNode q = cases.addObject().put("query", "如何退款").put("answerable", true).put("reviewed", true);
        q.putObject("labels").put("c1", 1);
        assertThrows(ResponseStatusException.class, () -> EvaluationCorpusService.validateQuestions(cases, Set.of("c1", "c2"), json));
        ((ObjectNode)q.get("labels")).put("c1", 2);
        var result = EvaluationCorpusService.validateQuestions(cases, Set.of("c1", "c2"), json);
        assertFalse(result.get(0).get("labels").has("c2"));
        assertEquals(2, result.get(0).path("labels").path("c1").asInt());
    }

    @Test void rejectsCrossSnapshotLabelsAndUnreviewedHardNegatives() {
        ArrayNode cases = json.createArrayNode();
        ObjectNode q = cases.addObject().put("query", "条件是什么").put("answerable", true);
        q.putObject("labels").put("foreign", 2);
        assertThrows(ResponseStatusException.class, () -> EvaluationCorpusService.validateQuestions(cases, Set.of("c1"), json));
        q.putObject("labels").put("c1", 1);
        q.putArray("hardNegativeIds").add("c1");
        assertThrows(ResponseStatusException.class, () -> EvaluationCorpusService.validateQuestions(cases, Set.of("c1"), json));
    }

    @Test void preventsIntentLeakageBetweenCalibrationAndAcceptance() {
        ArrayNode cases = json.createArrayNode();
        cases.addObject().put("query", "退款条件").put("intentGroup", "退款").put("split", "CALIBRATION");
        cases.addObject().put("query", "什么情况能退款").put("intentGroup", "退款").put("split", "ACCEPTANCE");
        assertThrows(ResponseStatusException.class, () -> EvaluationCorpusService.validateQuestions(cases, Set.of("c1"), json));
    }

    @Test void duplicateQuestionsCannotPretendToBeLanguageVariants() {
        ArrayNode cases = json.createArrayNode();
        cases.addObject().put("query", "退款条件").put("intentGroup", "退款");
        cases.addObject().put("query", "退款 条件").put("intentGroup", "退款");
        assertThrows(ResponseStatusException.class, () -> EvaluationCorpusService.validateQuestions(cases, Set.of("c1"), json));
    }
}
