package com.starsea.ai.evaluation.metrics;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static com.starsea.ai.evaluation.metrics.EvaluationMetrics.*;
import static com.starsea.ai.evaluation.metrics.EvaluationMetricsTest.*;
import static org.junit.jupiter.api.Assertions.*;

class EvaluationComparisonTest {
    @Test
    void paired_bootstrap_weights_intents_equally_and_is_reproducible_under_input_reordering() {
        List<QueryInput> baseline = new ArrayList<>();
        List<QueryInput> candidate = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            baseline.add(outcome("many" + i, "many", false));
            candidate.add(outcome("many" + i, "many", true));
        }
        baseline.add(outcome("single", "single", true));
        candidate.add(outcome("single", "single", false));
        Comparison first = compare(baseline, candidate, 1);
        MetricComparison hit = first.metrics().get("hit1");
        assertEquals(.5, hit.baseline());
        assertEquals(.5, hit.candidate());
        assertEquals(0.0, hit.delta());
        assertEquals(-1.0, hit.lower95());
        assertEquals(1.0, hit.upper95());
        assertEquals(2, hit.intentGroupCount());
        assertEquals(11, hit.pairedQuestionCount());
        assertEquals("INCONCLUSIVE", hit.evidence());
        Collections.reverse(candidate);
        Collections.reverse(baseline);
        assertEquals(first, compare(baseline, candidate, 1));
    }

    @Test
    void a_single_intent_does_not_fabricate_a_confidence_interval() {
        Comparison result = compare(List.of(outcome("q", "g", false)),
                List.of(outcome("q", "g", true)), 5);
        MetricComparison hit = result.metrics().get("hit1");
        assertEquals(1.0, hit.delta());
        assertNull(hit.lower95());
        assertNull(hit.upper95());
        assertEquals("INSUFFICIENT", hit.evidence());
    }

    @Test
    void multiple_independent_paired_improvements_produce_positive_empirical_bounds() {
        Comparison result = compare(List.of(outcome("a", "a", false), outcome("b", "b", false)),
                List.of(outcome("a", "a", true), outcome("b", "b", true)), 1);
        MetricComparison hit = result.metrics().get("hit1");
        assertEquals(1.0, hit.lower95());
        assertEquals(1.0, hit.upper95());
        assertEquals("IMPROVED", hit.evidence());
        assertEquals(10_000, result.bootstrapIterations());
        assertEquals(.95, result.confidenceLevel());
    }

    @Test
    void unmatched_questions_and_unresolved_labels_have_explicit_pair_denominators() {
        QueryInput known = query("q", "g", true, List.of(j("a", 2)), List.of(s("a", .8)));
        QueryInput unknown = query("q", "g", true, List.of(j("a", 2)), List.of(s("u", .9)));
        Comparison result = compare(List.of(known, outcome("base-only", "base", true)),
                List.of(unknown, outcome("candidate-only", "candidate", true)), 1);
        assertEquals(1, result.pairedQuestionCount());
        assertEquals(1, result.unpairedBaselineCount());
        assertEquals(1, result.unpairedCandidateCount());
        assertEquals(0, result.metrics().get("hit1").pairedQuestionCount());
        assertNull(result.metrics().get("hit1").delta());
        assertNull(result.metrics().get("hit1").lower95());
        assertEquals("INSUFFICIENT", result.metrics().get("hit1").evidence());
        assertEquals(0, compare(List.of(), List.of(), 5).intentGroupCount());
    }

    @Test
    void pairing_rejects_changed_truth_or_group_instead_of_claiming_comparable_results() {
        QueryInput baseline = outcome("q", "g", true);
        QueryInput changedTruth = query("q", "g", true, List.of(j("a", 1), j("n", 0)), List.of(s("a", .8)));
        assertThrows(IllegalArgumentException.class, () -> compare(List.of(baseline), List.of(changedTruth), 1));
        assertThrows(IllegalArgumentException.class, () -> compare(List.of(baseline), List.of(outcome("q", "other", true)), 1));
    }

    @Test
    void failed_queries_are_paired_ranking_regressions() {
        QueryInput baseline = query("q", "g", true, List.of(j("a", 2)), List.of(s("a", .8)));
        MetricComparison result = compare(List.of(baseline), List.of(failed("q", "g", true)), 5).metrics().get("hit1");
        assertEquals(-1.0, result.delta());
        assertEquals(1, result.pairedQuestionCount());
    }

    @Test
    void lower_latency_is_an_improvement_and_unreviewed_variants_do_not_supply_rank_evidence() {
        List<QueryInput> baseline = new ArrayList<>(), candidate = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            baseline.add(new QueryInput("q" + i, "g" + i, "c", "CALIBRATION", true, false,
                    List.of(j("a", 2)), List.of(s("a", .9)), null, 10, 10));
            candidate.add(new QueryInput("q" + i, "g" + i, "c", "CALIBRATION", true, false,
                    List.of(j("a", 2)), List.of(s("a", .9)), null, 5, 5));
        }
        Comparison comparison = compare(baseline, candidate, 5);
        assertEquals(-10.0, comparison.metrics().get("latencyMs").delta());
        assertEquals(-10.0, comparison.metrics().get("latencyMs").upper95());
        assertEquals("IMPROVED", comparison.metrics().get("latencyMs").evidence());
        assertNull(comparison.metrics().get("hit1").delta());
    }

    @Test
    void variant_group_comparisons_resample_groups_and_account_for_all_member_questions() {
        List<QueryInput> baseline = List.of(outcome("a1", "a", false), outcome("a2", "a", true),
                outcome("b1", "b", false), outcome("b2", "b", true), outcome("single", "single", true));
        List<QueryInput> candidate = List.of(outcome("a1", "a", true), outcome("a2", "a", true),
                outcome("b1", "b", true), outcome("b2", "b", true), outcome("single", "single", true));
        MetricComparison variants = compare(baseline, candidate, 1).metrics().get("variantGroupHitRate");
        assertEquals(4, variants.pairedQuestionCount());
        assertEquals(2, variants.intentGroupCount());
        assertEquals(1.0, variants.delta());
        assertEquals(1.0, variants.lower95());
    }

    @Test
    void absent_intent_groups_remain_independent_and_label_order_does_not_change_pairing() {
        QueryInput baseline = query("a", null, true, List.of(j("a", 2), j("n", 0)), List.of(s("n", .9)));
        QueryInput candidate = query("a", null, true, List.of(j("n", 0), j("a", 2)), List.of(s("a", .9)));
        Comparison result = compare(List.of(baseline, outcome("b", "", false)),
                List.of(candidate, outcome("b", "", true)), 1);
        assertEquals(2, result.metrics().get("hit1").intentGroupCount());
        assertEquals(1.0, result.metrics().get("hit1").lower95());
    }

    @Test
    void paired_ann_regression_and_variant_failure_remain_visible_with_supplemental_positive_scores() {
        List<Judgment> labels = List.of(j("answer", 2), hn("negative"));
        List<QueryInput> baseline = new ArrayList<>(), candidate = new ArrayList<>();
        for (String id : List.of("q1", "q2")) {
            baseline.add(query(id, "g", true, labels, List.of(s("answer", .99), s("negative", .5))));
            candidate.add(new QueryInput(id, "g", "general", "CALIBRATION", true, true, labels,
                    List.of(s("negative", .5)), null, 10, 5, List.of(s("answer", .99))));
        }
        Comparison comparison = compare(baseline, candidate, 5);
        assertEquals(-1.0, comparison.metrics().get("hit5").delta());
        assertEquals(-1.0, comparison.metrics().get("mrr10").delta());
        assertEquals(-1.0, comparison.metrics().get("ndcg5").delta());
        assertEquals(-1.0, comparison.metrics().get("variantGroupHitRate").delta());
        assertEquals(0.0, comparison.metrics().get("hardNegativeWinRate").delta());
        assertEquals(2, comparison.metrics().get("hardNegativeWinRate").pairedQuestionCount());
        Summary summary = calculate(candidate, 5, null);
        assertEquals(0.0, summary.variantGroupHitRate());
        assertEquals(1.0, summary.hardNegativeWinRate());
    }
}
