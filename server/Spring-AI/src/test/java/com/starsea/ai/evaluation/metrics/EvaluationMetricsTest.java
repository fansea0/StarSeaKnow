package com.starsea.ai.evaluation.metrics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.starsea.ai.evaluation.metrics.EvaluationMetrics.*;
import static org.junit.jupiter.api.Assertions.*;

class EvaluationMetricsTest {
    private static final double EPS = 1e-12;

    @Test
    void computes_known_label_ranking_and_graded_ndcg_without_applying_threshold() {
        QueryInput q = query("q", "g", true, List.of(j("a", 2), j("b", 1), hn("c")),
                List.of(s("a", .56), s("c", .54), s("b", .8)));
        CaseMetrics c = analyze(q, 1, .9);
        assertEquals(0.0, c.hit1());
        assertEquals(1.0, c.hit5());
        assertEquals(0.0, c.hitK());
        assertEquals(.5, c.mrr10());
        assertEquals((1 + 3 / log2(3)) / (3 + 1 / log2(3)), c.ndcg5(), EPS);
        assertEquals(.56, c.bestAnswerScore());
        assertEquals(.54, c.bestIrrelevantScore());
        assertEquals(.02, c.gap(), EPS);
        assertEquals(1.0, c.hardNegativeWin());
        assertEquals(0.0, c.evidenceRetention());
        assertEquals(1.0, c.answerableEmpty());
        assertEquals(0, c.returnedCount());
    }

    @Test
    void ideal_dcg_uses_all_known_judgments_including_unreturned_evidence() {
        QueryInput q = query("q", "g", true, List.of(j("a", 2), j("b", 2), j("c", 1)),
                List.of(s("a", .5)));
        assertEquals(3 / (3 + 3 / log2(3) + .5), analyze(q, 5, null).ndcg5(), EPS);
    }

    @Test
    void ranks_ten_even_when_requested_top_k_is_one_and_does_not_round_scores() {
        List<Judgment> labels = new ArrayList<>();
        List<ScoredChunk> scores = new ArrayList<>();
        for (int i = 1; i <= 11; i++) {
            labels.add(j("c" + i, i == 10 ? 2 : 0));
            scores.add(s("c" + i, 1 - i * .01));
        }
        CaseMetrics c = analyze(query("q", "g", true, labels, scores), 1, null);
        assertEquals(0.0, c.hit5());
        assertEquals(.1, c.mrr10(), EPS);
        assertEquals(0.0, c.evidenceRetention());
        assertEquals(10, c.firstAnswerRank());
        QueryInput nearTie = query("q", "g", true, List.of(j("z", 2), j("a", 0)),
                List.of(s("a", .50001), s("z", .50002)));
        assertEquals(1.0, analyze(nearTie, 1, null).hit1());
    }

    @Test
    void unknown_is_not_zero_and_only_resolvable_binary_metrics_are_reported() {
        QueryInput unknownFirst = query("q", "g", true, List.of(j("answer", 2)),
                List.of(s("unknown", .9), s("answer", .8)));
        CaseMetrics c = analyze(unknownFirst, 1, null);
        assertNull(c.hit1());
        assertEquals(1.0, c.hit5());
        assertNull(c.mrr10());
        assertNull(c.ndcg5());
        assertNull(c.evidenceRetention());
        assertNull(c.gap());
        assertEquals(1, c.unknownTopKCount());
        Summary summary = calculate(List.of(unknownFirst), 1, null);
        assertEquals(0, summary.denominators().get("hit1"));
        assertEquals(1, summary.denominators().get("hit5"));
        assertEquals(1, summary.unknownScoringCount());

        CaseMetrics knownFirst = analyze(query("q", "g", true, List.of(j("answer", 2)),
                List.of(s("answer", .9), s("unknown", .8))), 5, null);
        assertEquals(1.0, knownFirst.mrr10());
        assertEquals(1.0, knownFirst.evidenceRetention());
        assertNull(knownFirst.ndcg5());
    }

    @Test
    void unknowns_outside_requested_k_still_expose_incomplete_scoring_coverage() {
        CaseMetrics c = analyze(query("q", "g", true, List.of(j("a", 2)),
                List.of(s("a", .9), s("unknown", .8))), 1, null);
        assertEquals(0, c.unknownTopKCount());
        assertEquals(1, c.unknownScoringCount());
        assertNull(c.ndcg5());
    }

    @Test
    void null_threshold_preserves_negative_scores_and_equality_is_filtered() {
        QueryInput q = query("q", "g", true, List.of(j("a", 2), j("b", 0)),
                List.of(s("a", -.2), s("b", -.3)));
        assertEquals(1.0, analyze(q, 5, null).evidenceRetention());
        assertEquals(2, analyze(q, 5, null).returnedCount());
        assertEquals(1.0, analyze(q, 5, Math.nextDown(-.2)).evidenceRetention());
        assertEquals(0.0, analyze(q, 5, -.2).evidenceRetention());
        assertEquals(1.0, analyze(q, 5, -.2).answerableEmpty());
        assertEquals(1.0, analyze(q, 5, 0.0).hit1());
    }

    @Test
    void gap_and_hard_negative_metrics_require_scored_samples_and_ties_are_not_wins() {
        QueryInput tied = query("q", "g", true,
                List.of(j("z-answer", 2), hn("a-negative"), j("b-partial", 1)),
                List.of(s("z-answer", .5), s("a-negative", .5), s("b-partial", .8)));
        CaseMetrics c = analyze(tied, 1, null);
        assertEquals(0.0, c.gap());
        assertEquals(0.0, c.hardNegativeWin());
        assertTrue(c.hardNegativeTie());
        assertEquals(2, c.tiedScoreCount());
        assertEquals(3, c.firstAnswerRank());
        CaseMetrics missing = analyze(query("q", "g", true,
                List.of(j("a", 2), hn("b")), List.of(s("a", .5))), 5, null);
        assertNull(missing.gap());
        assertNull(missing.hardNegativeWin());
        assertEquals(0, missing.scoredHardNegativeCount());
        assertNull(analyze(query("q", "g", true, List.of(j("a", 2), j("b", 1)),
                List.of(s("a", .5), s("b", .9))), 5, null).gap());
    }

    @Test
    void unreviewed_and_missing_answer_judgments_do_not_fabricate_rank_metrics() {
        QueryInput unreviewed = new QueryInput("q", "g", "other", "CALIBRATION", true, false,
                List.of(j("a", 2)), List.of(s("a", .5)), null, 1, 2);
        Summary summary = calculate(List.of(unreviewed,
                query("q2", "g2", true, List.of(j("b", 1)), List.of(s("b", .8)))), 5, null);
        assertNull(summary.hit1());
        assertNull(summary.ndcg5());
        assertNull(summary.evidenceRetentionRate());
        assertEquals(1, summary.reviewedCount());
        assertEquals(1, summary.missingAnswerJudgmentCount());
    }

    @Test
    void failures_remain_ranking_misses_but_do_not_become_successful_empty_responses() {
        QueryInput good = query("good", "g1", true, List.of(j("a", 2)), List.of(s("a", .6)));
        QueryInput failed = failed("bad", "g2", true);
        QueryInput noAnswer = query("none", "g3", false, List.of(j("n", 0)), List.of(s("n", -.3)));
        Summary s = calculate(List.of(good, failed, noAnswer, failed("bad-no-answer", "g4", false)), 5, null);
        assertEquals(4, s.questionCount());
        assertEquals(2, s.completedCount());
        assertEquals(2, s.failedCount());
        assertEquals(.5, s.failureRate());
        assertEquals(.5, s.hit1());
        assertEquals(.5, s.evidenceRetentionRate());
        assertEquals(0.0, s.answerableEmptyRate());
        assertEquals(1.0, s.noAnswerFalsePositiveRate());
        assertEquals(2, s.denominators().get("hit1"));
        assertEquals(1, s.denominators().get("answerableEmptyRate"));
        assertEquals(1, s.denominators().get("noAnswerFalsePositiveRate"));
        assertEquals(2, s.denominators().get("p95Ms"));
        assertEquals(1, s.failedReviewedAnswerableCount());
        assertEquals(1, s.failedReviewedUnanswerableCount());
        assertNull(analyze(failed, 5, null).returnedCount());
        assertNull(analyze(failed, 5, null).latencyMs());
    }

    @Test
    void variant_rates_use_only_multiple_reviewed_answerable_questions_and_equal_group_weights() {
        List<QueryInput> queries = List.of(
                outcome("q1", "g1", true), outcome("q2", "g1", true),
                outcome("q3", "g2", true), outcome("q4", "g2", false),
                outcome("singleton", "g3", true),
                query("no-answer", "g3", false, List.of(), List.of()));
        Summary s = calculate(queries, 1, null);
        assertEquals(.5, s.variantGroupHitRate());
        assertEquals(2, s.denominators().get("variantGroupHitRate"));
        assertEquals(5.0 / 6, s.intentGroupMetrics().get("hit1"), EPS);
        assertEquals(3, s.intentGroupDenominators().get("hit1"));
        assertNull(calculate(List.of(outcome("only", "g", true)), 5, null).variantGroupHitRate());
    }

    @Test
    void unresolved_variants_are_null_unless_a_known_miss_already_proves_group_failure() {
        QueryInput unknown = query("unknown", "g", true, List.of(j("a", 2)), List.of(s("u", .9)));
        Summary unresolved = calculate(List.of(outcome("hit", "g", true), unknown), 1, null);
        assertNull(unresolved.variantGroupHitRate());
        assertEquals(1, unresolved.variantEligibleGroupCount());
        assertEquals(0, unresolved.denominators().get("variantGroupHitRate"));
        assertEquals(0.0, calculate(List.of(outcome("miss", "g", false), unknown), 1, null).variantGroupHitRate());
    }

    @Test
    void latency_uses_successful_query_sum_and_interpolated_percentiles() {
        List<QueryInput> inputs = new ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            inputs.add(new QueryInput("q" + i, "g" + i, "c", "CALIBRATION", false, true,
                    List.of(), List.of(), null, i * 5, i * 5));
        }
        Summary s = calculate(inputs, 5, null);
        assertEquals(25.0, s.p50Ms());
        assertEquals(38.5, s.p95Ms(), EPS);
        assertEquals(0.0, s.noAnswerFalsePositiveRate());
        assertNull(s.hit1());
    }

    @Test
    void calibration_uses_observed_scores_strict_boundaries_and_only_calibration_split() {
        QueryInput positive = query("p", "p", true, List.of(j("a", 2)), List.of(s("a", -.2)));
        QueryInput negative = query("n", "n", false, List.of(j("n", 0)), List.of(s("n", -.3)));
        QueryInput acceptance = new QueryInput("holdout", "h", "c", "ACCEPTANCE", true, true,
                List.of(j("h", 2)), List.of(s("h", .99)), null, 1, 2);
        List<CalibrationPoint> curve = calibrate(List.of(positive, negative, acceptance), 5);
        assertEquals(3, curve.size());
        assertNull(curve.get(0).threshold());
        assertEquals(1.0, curve.get(0).evidenceRetentionRate());
        assertEquals(1.0, curve.get(0).noAnswerFalsePositiveRate());
        assertEquals(-.3, curve.get(1).threshold());
        assertEquals(1.0, curve.get(1).evidenceRetentionRate());
        assertEquals(0.0, curve.get(1).noAnswerFalsePositiveRate());
        assertEquals(-.2, curve.get(2).threshold());
        assertEquals(0.0, curve.get(2).evidenceRetentionRate());
        assertEquals(1.0, curve.get(2).answerableEmptyRate());
        assertEquals(2, curve.get(2).questionCount());
        assertEquals(1, curve.get(2).denominators().get("evidenceRetentionRate"));
    }

    @Test
    void calibration_keeps_failed_queries_and_unknown_evidence_explicit_at_each_boundary() {
        QueryInput unknown = query("unknown", "g", true, List.of(j("a", 2)),
                List.of(s("u", .6), s("a", .5)));
        List<QueryInput> inputs = List.of(unknown, failed("failed", "f", true), failed("no-answer", "n", false));
        List<CalibrationPoint> curve = calibrate(inputs, 1);
        assertEquals(2, curve.size());
        CalibrationPoint disabled = curve.get(0);
        assertEquals(3, disabled.questionCount());
        assertEquals(1, disabled.completedCount());
        assertEquals(2, disabled.failedCount());
        assertEquals(0.0, disabled.evidenceRetentionRate());
        assertEquals(1, disabled.denominators().get("evidenceRetentionRate"));
        assertNull(disabled.noAnswerFalsePositiveRate());
        assertEquals(0.0, disabled.answerableEmptyRate());
        CalibrationPoint noReturn = curve.get(1);
        assertEquals(.6, noReturn.threshold());
        assertEquals(0.0, noReturn.evidenceRetentionRate());
        assertEquals(2, noReturn.denominators().get("evidenceRetentionRate"));
        assertEquals(1.0, noReturn.answerableEmptyRate());
    }

    @Test
    void missing_scores_for_any_required_judgment_prevent_claiming_a_gap_or_hard_negative_win() {
        CaseMetrics c = analyze(query("q", "g", true,
                List.of(j("a", 2), hn("n"), hn("unscored")),
                List.of(s("a", .8), s("n", .7))), 1, null);
        assertEquals(.8, c.bestAnswerScore());
        assertEquals(.7, c.bestHardNegativeScore());
        assertEquals(2, c.hardNegativeJudgmentCount());
        assertEquals(1, c.scoredHardNegativeCount());
        assertNull(c.gap());
        assertNull(c.hardNegativeWin());
        assertNull(c.hardNegativeTie());
    }

    @Test
    void gap_uses_judged_scores_beyond_top_k_and_preserves_negative_margins() {
        CaseMetrics c = analyze(query("q", "g", true, List.of(j("a", 2), hn("n"), j("p", 1)),
                List.of(s("p", .9), s("n", -.2), s("a", -.4))), 1, null);
        assertEquals(-.2, c.gap(), EPS);
        assertEquals(0.0, c.hardNegativeWin());
        assertEquals(0.0, c.hitK());
        assertEquals(1.0, c.hit5());
    }

    @Test
    void supplemental_answer_score_explains_ann_miss_without_inventing_a_retrieval_hit() {
        List<Judgment> labels = new ArrayList<>(List.of(j("answer", 2)));
        List<ScoredChunk> returned = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            labels.add(hn("negative" + i));
            returned.add(s("negative" + i, .5 - i * .01));
        }
        QueryInput q = new QueryInput("ann", "g", "general", "CALIBRATION", true, true,
                labels, returned, null, 10, 5, List.of(s("answer", .99)));
        CaseMetrics c = analyze(q, 5, null);
        assertEquals(0.0, c.hit1());
        assertEquals(0.0, c.hit5());
        assertEquals(0.0, c.hitK());
        assertEquals(0.0, c.mrr10());
        assertEquals(0.0, c.ndcg5());
        assertNull(c.firstAnswerRank());
        assertEquals(10, c.rankingCount());
        assertEquals(5, c.returnedCount());
        assertEquals(0, c.unknownScoringCount());
        assertEquals(.99, c.bestAnswerScore());
        assertEquals(.49, c.gap(), EPS);
        assertEquals(1.0, c.hardNegativeWin());
        assertEquals(1, c.scoredAnswerCount());
        assertEquals(10, c.scoredHardNegativeCount());
        assertEquals(0.0, c.evidenceRetention());
        assertEquals(0, analyze(q, 5, .6).returnedCount());
        assertEquals(1.0, analyze(q, 5, .6).answerableEmpty());

        Summary summary = calculate(List.of(q), 5, null);
        assertEquals(0.0, summary.hit5());
        assertEquals(1.0, summary.hardNegativeWinRate());
        List<CalibrationPoint> curve = calibrate(List.of(q), 5);
        assertEquals(6, curve.size());
        assertEquals(.5, curve.get(curve.size() - 1).threshold());
        assertTrue(curve.stream().allMatch(point -> point.evidenceRetentionRate() == 0.0));
    }

    @Test
    void supplemental_scores_neither_change_actual_rank_ties_nor_unknown_coverage() {
        QueryInput q = new QueryInput("q", "g", "general", "CALIBRATION", true, true,
                List.of(j("answer", 2), hn("negative")), List.of(s("answer", .4)), null, 1, 2,
                List.of(s("negative", .4), s("unjudged", .9)));
        CaseMetrics c = analyze(q, 5, null);
        assertEquals(1, c.firstAnswerRank());
        assertEquals(1, c.rankingCount());
        assertEquals(0, c.tiedScoreCount());
        assertEquals(0, c.unknownTopKCount());
        assertEquals(0, c.unknownScoringCount());
        assertEquals(1.0, c.hit1());
        assertEquals(1.0, c.mrr10());
        assertEquals(1.0, c.ndcg5());
        assertEquals(Boolean.TRUE, c.hardNegativeTie());
        assertEquals(0.0, c.hardNegativeWin());
    }

    @Test
    void supplemental_scores_do_not_create_no_answer_false_positives_or_calibration_thresholds() {
        QueryInput q = new QueryInput("q", "g", "general", "CALIBRATION", false, true,
                List.of(j("negative", 0)), List.of(), null, 1, 2, List.of(s("negative", .99)));
        CaseMetrics c = analyze(q, 5, null);
        assertEquals(0, c.returnedCount());
        assertEquals(0.0, c.noAnswerFalsePositive());
        assertEquals(.99, c.bestIrrelevantScore());
        List<CalibrationPoint> curve = calibrate(List.of(q), 5);
        assertEquals(1, curve.size());
        assertEquals(0.0, curve.get(0).noAnswerFalsePositiveRate());
        assertEquals(1, curve.get(0).denominators().get("noAnswerFalsePositiveRate"));
    }

    @Test
    void matching_ranked_and_supplemental_scores_merge_once_but_conflicting_scores_are_rejected() {
        List<Judgment> labels = List.of(j("answer", 2), hn("negative"));
        List<ScoredChunk> ranking = List.of(s("answer", .6), s("negative", .5));
        QueryInput q = new QueryInput("q", "g", "general", "CALIBRATION", true, true,
                labels, ranking, null, 1, 2, ranking);
        CaseMetrics c = analyze(q, 5, null);
        assertEquals(1, c.scoredAnswerCount());
        assertEquals(1, c.scoredIrrelevantCount());
        assertEquals(1, c.scoredHardNegativeCount());
        assertEquals(.1, c.gap(), EPS);
        assertEquals(1.0, c.hardNegativeWin());
        assertThrows(IllegalArgumentException.class, () -> new QueryInput("q", "g", "general", "CALIBRATION",
                true, true, labels, ranking, null, 1, 2, List.of(s("answer", Math.nextUp(.6)))));
        assertThrows(IllegalArgumentException.class, () -> new QueryInput("q", "g", "general", "CALIBRATION",
                true, true, labels, ranking, null, 1, 2, List.of(s("other", .6), s("other", .5))));
    }

    @Test
    void supplemental_pool_must_cover_all_required_judgments_for_gap_and_hard_negative_metrics() {
        List<Judgment> labels = List.of(j("answer", 2), j("other-answer", 2), hn("negative"), hn("other-negative"));
        List<ScoredChunk> ranking = List.of(s("negative", .8));
        QueryInput partial = new QueryInput("q", "g", "general", "CALIBRATION", true, true,
                labels, ranking, null, 1, 2, List.of(s("answer", .9), s("other-negative", .95)));
        assertEquals(1, analyze(partial, 5, null).scoredAnswerCount());
        assertEquals(2, analyze(partial, 5, null).scoredHardNegativeCount());
        assertNull(analyze(partial, 5, null).gap());
        assertNull(analyze(partial, 5, null).hardNegativeWin());
        QueryInput complete = new QueryInput("q", "g", "general", "CALIBRATION", true, true,
                labels, ranking, null, 1, 2,
                List.of(s("answer", .9), s("other-answer", .85), s("other-negative", .95)));
        assertEquals(-.05, analyze(complete, 5, null).gap(), EPS);
        assertEquals(0.0, analyze(complete, 5, null).hardNegativeWin());
        assertNull(analyze(complete, 5, null).firstAnswerRank());
    }

    @Test
    void supplemental_scores_are_snapshotted_and_legacy_constructor_defaults_to_empty_pool() {
        List<ScoredChunk> supplemental = new ArrayList<>(List.of(s("answer", .9)));
        QueryInput q = new QueryInput("q", "g", "general", "CALIBRATION", true, true,
                List.of(j("answer", 2), hn("negative")), List.of(s("negative", .8)), null, 1, 2, supplemental);
        supplemental.clear();
        assertEquals(.9, analyze(q, 5, null).bestAnswerScore());
        assertThrows(UnsupportedOperationException.class, () -> q.judgedScores().clear());
        assertTrue(outcome("legacy", "g", true).judgedScores().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> new QueryInput("q", "g", "general", "CALIBRATION",
                true, true, List.of(), List.of(), null, 1, 2, null));
    }

    @Test
    void zero_score_signs_are_a_tie_and_neither_passes_a_zero_threshold() {
        QueryInput q = query("q", "g", true, List.of(j("a-answer", 2), hn("z-negative")),
                List.of(s("z-negative", 0.0), s("a-answer", -0.0)));
        CaseMetrics c = analyze(q, 1, 0.0);
        assertEquals(1.0, c.hit1());
        assertEquals(0, c.returnedCount());
        assertEquals(2, c.tiedScoreCount());
        assertTrue(c.hardNegativeTie());
        assertEquals(2, calibrate(List.of(q), 5).size());
    }

    @Test
    void long_latency_totals_do_not_overflow_before_conversion_to_double() {
        QueryInput q = new QueryInput("q", "g", "c", "CALIBRATION", false, true,
                List.of(), List.of(), "  ", Long.MAX_VALUE, Long.MAX_VALUE);
        assertEquals(2.0 * Long.MAX_VALUE, analyze(q, 1, null).latencyMs());
        assertEquals(1, calculate(List.of(q), 1, null).completedCount());
    }

    @Test
    void empty_outputs_have_null_metrics_and_preserve_plan_json_field_names() throws Exception {
        Summary s = calculate(List.of(), 5, null);
        JsonNode json = new ObjectMapper().valueToTree(s);
        for (String field : List.of("hit1", "hit5", "mrr10", "ndcg5", "hardNegativeWinRate",
                "variantGroupHitRate", "noAnswerFalsePositiveRate", "evidenceRetentionRate",
                "answerableEmptyRate", "p50Ms", "p95Ms")) {
            assertTrue(json.has(field), field);
            assertTrue(json.get(field).isNull(), field);
        }
        assertEquals(0, s.questionCount());
        assertNull(s.failureRate());
        assertEquals(1, calibrate(List.of(), 5).size());
        assertThrows(UnsupportedOperationException.class, () -> s.denominators().put("hit1", 3));
    }

    @Test
    void invalid_inputs_fail_clearly_and_inputs_are_snapshotted() {
        assertThrows(IllegalArgumentException.class, () -> s("a", Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> s("a", Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> j("a", 3));
        assertThrows(IllegalArgumentException.class, () -> new Judgment("a", 2, true));
        assertThrows(IllegalArgumentException.class, () -> calculate(List.of(), 0, null));
        assertThrows(IllegalArgumentException.class, () -> calculate(List.of(), 1, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> calculate(null, 1, null));
        assertThrows(IllegalArgumentException.class, () -> analyze(null, 1, null));
        assertThrows(IllegalArgumentException.class, () -> query(" ", "g", true, List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> query("q", "g", false, List.of(j("a", 2)), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new QueryInput("q", "g", "c", "CALIBRATION",
                true, true, List.of(), List.of(), null, -1, 0));
        assertThrows(IllegalArgumentException.class, () -> query("q", "g", true,
                List.of(j("a", 2), j("a", 0)), List.of()));
        assertThrows(IllegalArgumentException.class, () -> query("q", "g", true,
                List.of(j("a", 2)), List.of(s("a", .3), s("a", .4))));
        QueryInput q = outcome("q", "g", true);
        assertThrows(IllegalArgumentException.class, () -> calculate(List.of(q, q), 5, null));
        List<Judgment> labels = new ArrayList<>(List.of(j("a", 2)));
        List<ScoredChunk> scores = new ArrayList<>(List.of(s("a", .7)));
        QueryInput copied = query("copy", "g", true, labels, scores);
        labels.clear();
        scores.clear();
        assertEquals(1.0, analyze(copied, 5, null).hit1());
    }

    static QueryInput query(String id, String group, boolean answerable,
                            List<Judgment> judgments, List<ScoredChunk> ranking) {
        return new QueryInput(id, group, "general", "CALIBRATION", answerable, true,
                judgments, ranking, null, 10, 5);
    }

    static QueryInput outcome(String id, String group, boolean hit) {
        return query(id, group, true, List.of(j("a", 2), j("n", 0)),
                hit ? List.of(s("a", .9), s("n", .8)) : List.of(s("n", .9)));
    }

    static QueryInput failed(String id, String group, boolean answerable) {
        return new QueryInput(id, group, "general", "CALIBRATION", answerable, true,
                answerable ? List.of(j("a", 2)) : List.of(), List.of(s("a", .9)), "timeout", 1000, 2000);
    }

    static Judgment j(String id, int label) { return new Judgment(id, label, false); }
    static Judgment hn(String id) { return new Judgment(id, 0, true); }
    static ScoredChunk s(String id, double score) { return new ScoredChunk(id, score); }
    private static double log2(double n) { return Math.log(n) / Math.log(2); }
}
