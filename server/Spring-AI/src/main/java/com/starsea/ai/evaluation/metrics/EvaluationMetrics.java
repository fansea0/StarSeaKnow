package com.starsea.ai.evaluation.metrics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * Pure evaluation over a frozen judgment pool; never infers a relevance label from a score.
 *
 * <p>Request at least max(10, topK) unfiltered candidates and supply the actual retrieval output
 * in {@link QueryInput#ranking()}. Supply independent scores for judged chunks in
 * {@link QueryInput#judgedScores()}; these never become retrieval candidates. Only score
 * diagnostics use the union of both lists. The caller is responsible for distinguishing a small
 * corpus from a truncated ranking and for enforcing snapshot/version consistency.
 * No complete-recall or acceptance claim is made here.
 *
 * <p>Only reviewed answerable questions with a known grade-2 judgment enter answer ranking
 * metrics. Unknowns yield null when they prevent determining a metric; a known hit still
 * establishes Hit@K despite other unknowns. A failed eligible query is a ranking/evidence miss,
 * but is never a successful empty response or a latency sample. Every aggregate exposes its
 * actual denominator. All times are milliseconds and must already exclude warmup.
 */
public final class EvaluationMetrics {
    private EvaluationMetrics() {}

    public record ScoredChunk(String chunkId, double score) {
        public ScoredChunk {
            requireId(chunkId, "chunkId");
            if (!Double.isFinite(score)) {
                throw new IllegalArgumentException("Scores must be finite");
            }
            // Signed zero denotes the same score and must use the same deterministic tie rule.
            if (score == 0) score = 0;
        }
    }

    public record Judgment(String chunkId, int relevance, boolean hardNegative) {
        public Judgment {
            requireId(chunkId, "chunkId");
            if (relevance < 0 || relevance > 2 || (hardNegative && relevance != 0)) {
                throw new IllegalArgumentException("Relevance must be 0/1/2; hard negatives must have relevance 0");
            }
        }
    }

    /**
     * Null/blank error means success; absent intent groups are treated as separate questions.
     * ranking contains only actual retrieved chunks, sorted by full score and chunk ID for
     * evaluation. judgedScores contains supplemental scores, never additional retrieval hits.
     * Each list must have unique chunk IDs. An ID shared by both lists must have exactly the
     * same score and is counted once in score diagnostics; conflicting scores are rejected.
     */
    public record QueryInput(String questionId, String intentGroup, String category, String split,
                             boolean answerable, boolean reviewed, List<Judgment> judgments,
                             List<ScoredChunk> ranking, String error, long embeddingMs, long searchMs,
                             List<ScoredChunk> judgedScores) {
        public QueryInput {
            requireId(questionId, "questionId");
            judgments = snapshot(judgments, "judgments");
            ranking = snapshot(ranking, "ranking");
            judgedScores = snapshot(judgedScores, "judgedScores");
            requireUnique(judgments, Judgment::chunkId, "judgment chunkId");
            requireUnique(ranking, ScoredChunk::chunkId, "ranked chunkId");
            requireUnique(judgedScores, ScoredChunk::chunkId, "supplemental chunkId");
            mergeScores(ranking, judgedScores);
            if (embeddingMs < 0 || searchMs < 0) {
                throw new IllegalArgumentException("Query durations must be nonnegative");
            }
            if (!answerable && judgments.stream().anyMatch(j -> j.relevance() == 2)) {
                throw new IllegalArgumentException("An unanswerable question cannot have answer-supporting judgments");
            }
        }

        /** Source-compatible constructor for callers supplying only actual retrieval scores. */
        public QueryInput(String questionId, String intentGroup, String category, String split,
                          boolean answerable, boolean reviewed, List<Judgment> judgments,
                          List<ScoredChunk> ranking, String error, long embeddingMs, long searchMs) {
            this(questionId, intentGroup, category, split, answerable, reviewed, judgments,
                    ranking, error, embeddingMs, searchMs, List.of());
        }
    }

    /**
     * unknownTopKCount counts unjudged returned candidates within requested K; unknownScoringCount
     * covers max(10,K) and must also be checked for report completeness. tiedScoreCount counts
     * actual retrieved candidates sharing a score with another retrieved candidate. firstAnswerRank
     * is absent when no answer-supporting chunk was retrieved, even if independently scored.
     * Score maxima and scored judgment counts describe the merged, deduplicated diagnostic pool;
     * gap/win require all relevant judgments scored. Supplemental scores do not affect ranking,
     * unknown coverage, threshold policy or variant metrics.
     */
    public record CaseMetrics(String questionId, boolean completed, int rankingCount,
                              int unknownTopKCount, int unknownScoringCount, int tiedScoreCount,
                              int knownAnswerCount, int scoredAnswerCount, int knownIrrelevantCount,
                              int scoredIrrelevantCount, int hardNegativeJudgmentCount,
                              int scoredHardNegativeCount, Integer firstAnswerRank, Integer returnedCount,
                              Double hit1, Double hit5, Double hitK, Double mrr10, Double ndcg5,
                              Double bestAnswerScore, Double bestIrrelevantScore, Double bestHardNegativeScore,
                              Double gap, Double hardNegativeWin, Boolean hardNegativeTie,
                              Double noAnswerFalsePositive, Double evidenceRetention,
                              Double answerableEmpty, Double latencyMs) {}

    /**
     * Field names through p95Ms are the report JSON contract. denominators uses the same metric
     * names (including hitK and failureRate). Group aggregates are means of per-intent means,
     * whereas headline metrics are per-question means. Null always means unavailable.
     */
    public record Summary(int questionCount, int completedCount, int failedCount,
                          int answerableCount, int unanswerableCount, int reviewedCount, int unknownTopKCount,
                          Double hit1, Double hit5, Double mrr10, Double ndcg5, Double hardNegativeWinRate,
                          Double variantGroupHitRate, Double noAnswerFalsePositiveRate,
                          Double evidenceRetentionRate, Double answerableEmptyRate, Double p50Ms, Double p95Ms,
                          Double hitK, Double failureRate, int unknownScoringCount,
                          int failedReviewedAnswerableCount, int failedReviewedUnanswerableCount,
                          int missingAnswerJudgmentCount, int variantEligibleGroupCount,
                          Map<String, Integer> denominators, Map<String, Double> intentGroupMetrics,
                          Map<String, Integer> intentGroupDenominators) {
        public Summary {
            denominators = immutableMap(denominators);
            intentGroupMetrics = immutableMap(intentGroupMetrics);
            intentGroupDenominators = immutableMap(intentGroupDenominators);
        }
    }

    public record CalibrationPoint(Double threshold, Double evidenceRetentionRate,
                                   Double noAnswerFalsePositiveRate, Double answerableEmptyRate,
                                   int questionCount, int completedCount, int failedCount,
                                   Map<String, Integer> denominators) {
        public CalibrationPoint { denominators = immutableMap(denominators); }
    }

    /** Delta and bounds are candidate minus baseline, with equal weight per intent group. */
    public record MetricComparison(Double baseline, Double candidate, Double delta,
                                   Double lower95, Double upper95, int pairedQuestionCount,
                                   int intentGroupCount, String evidence) {}

    /** Paired percentiles describe empirical uncertainty, not a guarantee or delivery verdict. */
    public record Comparison(int baselineQuestionCount, int candidateQuestionCount, int pairedQuestionCount,
                             int unpairedBaselineCount, int unpairedCandidateCount, int intentGroupCount,
                             int bootstrapIterations, long bootstrapSeed, double confidenceLevel,
                             Map<String, MetricComparison> metrics) {
        public Comparison { metrics = immutableMap(metrics); }
    }

    public static Summary calculate(List<QueryInput> inputs, int topK, Double threshold) {
        validateOptions(topK, threshold);
        List<QueryInput> queries = checkedQueries(inputs);
        List<CaseMetrics> cases = queries.stream().map(q -> analyzePrepared(new Prepared(q), topK, threshold)).toList();
        Map<String, List<Double>> samples = new LinkedHashMap<>();
        Map<String, Map<String, List<Double>>> groups = new LinkedHashMap<>();
        Map<String, List<CaseMetrics>> variants = new TreeMap<>();
        int completed = 0, answerable = 0, reviewed = 0, unknownK = 0, unknownScoring = 0;
        int failedAnswerable = 0, failedUnanswerable = 0, missingAnswer = 0;
        for (int i = 0; i < queries.size(); i++) {
            QueryInput q = queries.get(i);
            CaseMetrics c = cases.get(i);
            completed += c.completed() ? 1 : 0;
            answerable += q.answerable() ? 1 : 0;
            reviewed += q.reviewed() ? 1 : 0;
            unknownK += c.unknownTopKCount();
            unknownScoring += c.unknownScoringCount();
            if (q.reviewed()) {
                if (!c.completed()) {
                    if (q.answerable()) failedAnswerable++; else failedUnanswerable++;
                }
                if (q.answerable()) {
                    variants.computeIfAbsent(groupKey(q), key -> new ArrayList<>()).add(c);
                    if (c.knownAnswerCount() == 0) missingAnswer++;
                }
            }
            for (var entry : values(c).entrySet()) {
                List<Double> values = samples.computeIfAbsent(entry.getKey(), key -> new ArrayList<>());
                Map<String, List<Double>> metricGroups = groups.computeIfAbsent(entry.getKey(), key -> new TreeMap<>());
                if (entry.getValue() != null) {
                    values.add(entry.getValue());
                    metricGroups.computeIfAbsent(groupKey(q), key -> new ArrayList<>()).add(entry.getValue());
                }
            }
        }
        Map<String, Integer> denominators = new LinkedHashMap<>();
        Map<String, Double> means = new LinkedHashMap<>();
        Map<String, Double> groupMeans = new LinkedHashMap<>();
        Map<String, Integer> groupDenominators = new LinkedHashMap<>();
        for (String metric : metricNames()) {
            List<Double> values = samples.getOrDefault(metric, List.of());
            means.put(metric, mean(values));
            denominators.put(metric, values.size());
            List<Double> perGroup = groups.getOrDefault(metric, Map.of()).values().stream()
                    .map(EvaluationMetrics::mean).toList();
            groupMeans.put(metric, mean(perGroup));
            groupDenominators.put(metric, perGroup.size());
        }
        List<Double> variantValues = new ArrayList<>();
        int eligibleGroups = 0;
        for (List<CaseMetrics> group : variants.values()) {
            if (group.size() < 2) continue;
            eligibleGroups++;
            Double allHit = allHit(group);
            if (allHit != null) variantValues.add(allHit);
        }
        Double variantRate = mean(variantValues);
        denominators.put("variantGroupHitRate", variantValues.size());
        groupMeans.put("variantGroupHitRate", variantRate);
        groupDenominators.put("variantGroupHitRate", variantValues.size());
        List<Double> latencies = samples.getOrDefault("latencyMs", List.of());
        denominators.put("p50Ms", latencies.size());
        denominators.put("p95Ms", latencies.size());
        return new Summary(queries.size(), completed, queries.size() - completed, answerable,
                queries.size() - answerable, reviewed, unknownK, means.get("hit1"), means.get("hit5"),
                means.get("mrr10"), means.get("ndcg5"), means.get("hardNegativeWinRate"), variantRate,
                means.get("noAnswerFalsePositiveRate"), means.get("evidenceRetentionRate"),
                means.get("answerableEmptyRate"), percentile(latencies, .5), percentile(latencies, .95),
                means.get("hitK"), means.get("failureRate"), unknownScoring, failedAnswerable,
                failedUnanswerable, missingAnswer, eligibleGroups, denominators, groupMeans, groupDenominators);
    }

    public static CaseMetrics analyze(QueryInput input, int topK, Double threshold) {
        validateOptions(topK, threshold);
        if (input == null) throw new IllegalArgumentException("Query must not be null");
        return analyzePrepared(new Prepared(input), topK, threshold);
    }

    /**
     * Uses only CALIBRATION questions. First row disables filtering; remaining rows are distinct
     * observed Top-K scores in ascending order. Under strict >, the largest observed score is
     * itself the no-return boundary, with no epsilon or artificial nonfinite threshold required.
     * Empty calibration input yields one disabled-filter row with unavailable rates.
     */
    public static List<CalibrationPoint> calibrate(List<QueryInput> inputs, int topK) {
        validateOptions(topK, null);
        List<Prepared> prepared = checkedQueries(inputs).stream()
                .filter(q -> "CALIBRATION".equals(q.split())).map(Prepared::new).toList();
        TreeSet<Double> thresholds = new TreeSet<>();
        List<ThresholdSample> samples = new ArrayList<>();
        for (Prepared p : prepared) {
            List<ScoredChunk> top = first(p.ranking, topK);
            top.forEach(s -> thresholds.add(s.score()));
            samples.add(new ThresholdSample(p, top));
        }
        List<CalibrationPoint> result = new ArrayList<>();
        result.add(calibrationPoint(samples, null));
        thresholds.forEach(t -> result.add(calibrationPoint(samples, t)));
        return List.copyOf(result);
    }

    /**
     * Pairs question IDs, validates unchanged metadata/judgments, then resamples whole intent
     * groups with seed 20260904, 10,000 replicates and interpolated 95% percentile bounds.
     * Fewer than two applicable groups yields null bounds and INSUFFICIENT evidence.
     */
    public static Comparison compare(List<QueryInput> baseline, List<QueryInput> candidate, int topK) {
        validateOptions(topK, null);
        return PairedBootstrap.compare(checkedQueries(baseline), checkedQueries(candidate), topK);
    }

    private static CaseMetrics analyzePrepared(Prepared p, int topK, Double threshold) {
        QueryInput q = p.input;
        List<ScoredChunk> top = first(p.ranking, topK);
        List<ScoredChunk> returned = top.stream().filter(s -> passes(s.score(), threshold)).toList();
        Double answer = null, irrelevant = null, hardNegative = null;
        int scoredAnswers = 0, scoredIrrelevant = 0, scoredHard = 0;
        Integer answerRank = null;
        int tied = 0;
        for (int i = 0; i < p.ranking.size(); i++) {
            ScoredChunk s = p.ranking.get(i);
            if ((i > 0 && p.ranking.get(i - 1).score() == s.score())
                    || (i + 1 < p.ranking.size() && p.ranking.get(i + 1).score() == s.score())) tied++;
            Judgment label = p.labels.get(s.chunkId());
            if (q.reviewed() && label != null && label.relevance() == 2 && answerRank == null) {
                answerRank = i + 1;
            }
        }
        for (ScoredChunk s : p.scorePool) {
            Judgment label = p.labels.get(s.chunkId());
            if (!q.reviewed() || label == null) continue;
            if (label.relevance() == 2) {
                scoredAnswers++;
                answer = max(answer, s.score());
            } else if (label.relevance() == 0) {
                scoredIrrelevant++;
                irrelevant = max(irrelevant, s.score());
                if (label.hardNegative()) {
                    scoredHard++;
                    hardNegative = max(hardNegative, s.score());
                }
            }
        }
        boolean gapKnown = answer != null && irrelevant != null
                && scoredAnswers == p.answers && scoredIrrelevant == p.irrelevant;
        boolean hardKnown = p.eligibleAnswer && answer != null && hardNegative != null
                && scoredAnswers == p.answers && scoredHard == p.hardNegatives;
        return new CaseMetrics(q.questionId(), p.completed, p.ranking.size(), unknown(top, p.labels),
                unknown(first(p.ranking, Math.max(10, topK)), p.labels), tied,
                p.answers, scoredAnswers, p.irrelevant, scoredIrrelevant, p.hardNegatives, scoredHard,
                answerRank, p.completed ? returned.size() : null,
                hit(p, first(p.ranking, 1)), hit(p, first(p.ranking, 5)), hit(p, top),
                mrr(p), ndcg(p), answer, irrelevant, hardNegative,
                gapKnown ? answer - irrelevant : null,
                hardKnown ? indicator(answer > hardNegative) : null,
                hardKnown ? answer.doubleValue() == hardNegative.doubleValue() : null,
                q.reviewed() && !q.answerable() && p.completed ? indicator(!returned.isEmpty()) : null,
                hit(p, returned), q.reviewed() && q.answerable() && p.completed ? indicator(returned.isEmpty()) : null,
                p.completed ? (double) q.embeddingMs() + q.searchMs() : null);
    }

    private static Double hit(Prepared p, List<ScoredChunk> candidates) {
        if (!p.eligibleAnswer) return null;
        if (!p.completed) return 0.0;
        boolean unknown = false;
        for (ScoredChunk score : candidates) {
            Judgment judgment = p.labels.get(score.chunkId());
            if (judgment == null) unknown = true;
            else if (judgment.relevance() == 2) return 1.0;
        }
        return unknown ? null : 0.0;
    }

    private static Double mrr(Prepared p) {
        if (!p.eligibleAnswer) return null;
        if (!p.completed) return 0.0;
        List<ScoredChunk> top = first(p.ranking, 10);
        for (int i = 0; i < top.size(); i++) {
            Judgment label = p.labels.get(top.get(i).chunkId());
            if (label == null) return null;
            if (label.relevance() == 2) return 1.0 / (i + 1);
        }
        return 0.0;
    }

    private static Double ndcg(Prepared p) {
        if (!p.eligibleAnswer) return null;
        if (!p.completed) return 0.0;
        List<ScoredChunk> top = first(p.ranking, 5);
        double actual = 0;
        for (int i = 0; i < top.size(); i++) {
            Judgment label = p.labels.get(top.get(i).chunkId());
            if (label == null) return null;
            actual += gain(label.relevance()) / log2(i + 2);
        }
        List<Integer> ideal = p.labels.values().stream().map(Judgment::relevance)
                .sorted(Comparator.reverseOrder()).limit(5).toList();
        double best = 0;
        for (int i = 0; i < ideal.size(); i++) best += gain(ideal.get(i)) / log2(i + 2);
        return best == 0 ? null : actual / best;
    }

    private static CalibrationPoint calibrationPoint(List<ThresholdSample> samples, Double threshold) {
        int completed = 0;
        List<Double> evidence = new ArrayList<>(), falsePositives = new ArrayList<>(), empty = new ArrayList<>();
        for (ThresholdSample sample : samples) {
            Prepared p = sample.prepared;
            QueryInput q = p.input;
            if (p.completed) completed++;
            if (!q.reviewed()) continue;
            boolean hasReturn = passes(sample.highest, threshold);
            if (!q.answerable() && p.completed) falsePositives.add(indicator(hasReturn));
            if (q.answerable() && p.completed) empty.add(indicator(!hasReturn));
            if (p.eligibleAnswer) {
                if (!p.completed) evidence.add(0.0);
                else if (passes(sample.answer, threshold)) evidence.add(1.0);
                else if (!passes(sample.unknown, threshold)) evidence.add(0.0);
            }
        }
        return new CalibrationPoint(threshold, mean(evidence), mean(falsePositives), mean(empty),
                samples.size(), completed, samples.size() - completed,
                Map.of("evidenceRetentionRate", evidence.size(), "noAnswerFalsePositiveRate", falsePositives.size(),
                        "answerableEmptyRate", empty.size()));
    }

    private static final class ThresholdSample {
        final Prepared prepared;
        Double highest, answer, unknown;

        ThresholdSample(Prepared prepared, List<ScoredChunk> top) {
            this.prepared = prepared;
            for (ScoredChunk score : top) {
                highest = max(highest, score.score());
                Judgment label = prepared.labels.get(score.chunkId());
                if (label == null) unknown = max(unknown, score.score());
                else if (label.relevance() == 2) answer = max(answer, score.score());
            }
        }
    }

    private static final class Prepared {
        final QueryInput input;
        final boolean completed, eligibleAnswer;
        final Map<String, Judgment> labels = new LinkedHashMap<>();
        final List<ScoredChunk> ranking, scorePool;
        final int answers, irrelevant, hardNegatives;

        Prepared(QueryInput input) {
            this.input = input;
            completed = input.error() == null || input.error().isBlank();
            input.judgments().forEach(j -> labels.put(j.chunkId(), j));
            answers = (int) labels.values().stream().filter(j -> j.relevance() == 2).count();
            irrelevant = (int) labels.values().stream().filter(j -> j.relevance() == 0).count();
            hardNegatives = (int) labels.values().stream().filter(Judgment::hardNegative).count();
            eligibleAnswer = input.reviewed() && input.answerable() && answers > 0;
            ranking = completed ? input.ranking().stream()
                    .sorted(Comparator.comparingDouble(ScoredChunk::score).reversed().thenComparing(ScoredChunk::chunkId))
                    .toList() : List.of();
            scorePool = completed ? mergeScores(input.ranking(), input.judgedScores()) : List.of();
        }
    }

    private static List<ScoredChunk> mergeScores(List<ScoredChunk> ranking, List<ScoredChunk> supplemental) {
        Map<String, ScoredChunk> scores = new LinkedHashMap<>();
        ranking.forEach(score -> scores.put(score.chunkId(), score));
        for (ScoredChunk score : supplemental) {
            ScoredChunk previous = scores.putIfAbsent(score.chunkId(), score);
            if (previous != null && Double.compare(previous.score(), score.score()) != 0) {
                throw new IllegalArgumentException("Conflicting ranked and supplemental scores for chunkId: " + score.chunkId());
            }
        }
        return List.copyOf(scores.values());
    }

    static Map<String, Double> values(CaseMetrics c) {
        Map<String, Double> values = new LinkedHashMap<>();
        values.put("hit1", c.hit1());
        values.put("hit5", c.hit5());
        values.put("hitK", c.hitK());
        values.put("mrr10", c.mrr10());
        values.put("ndcg5", c.ndcg5());
        values.put("hardNegativeWinRate", c.hardNegativeWin());
        values.put("noAnswerFalsePositiveRate", c.noAnswerFalsePositive());
        values.put("evidenceRetentionRate", c.evidenceRetention());
        values.put("answerableEmptyRate", c.answerableEmpty());
        values.put("latencyMs", c.latencyMs());
        values.put("failureRate", c.completed() ? 0.0 : 1.0);
        return values;
    }

    static List<String> metricNames() {
        return List.of("hit1", "hit5", "hitK", "mrr10", "ndcg5", "hardNegativeWinRate",
                "noAnswerFalsePositiveRate", "evidenceRetentionRate", "answerableEmptyRate", "latencyMs", "failureRate");
    }

    static Double allHit(List<CaseMetrics> cases) {
        if (cases.stream().anyMatch(c -> Objects.equals(c.hitK(), 0.0))) return 0.0;
        return cases.stream().anyMatch(c -> c.hitK() == null) ? null : 1.0;
    }

    static String groupKey(QueryInput q) {
        return q.intentGroup() == null || q.intentGroup().isBlank()
                ? "question:" + q.questionId() : "intent:" + q.intentGroup();
    }

    static Double mean(List<Double> values) {
        return values.isEmpty() ? null : values.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
    }

    /** Linear interpolation at index (n-1)*p (R-7 convention). */
    static Double percentile(List<Double> values, double p) {
        if (values.isEmpty()) return null;
        List<Double> sorted = values.stream().sorted().toList();
        double index = (sorted.size() - 1) * p;
        int low = (int) Math.floor(index);
        int high = (int) Math.ceil(index);
        return sorted.get(low) + (sorted.get(high) - sorted.get(low)) * (index - low);
    }

    private static boolean passes(Double score, Double threshold) {
        return score != null && (threshold == null || score > threshold);
    }

    private static Double max(Double previous, double value) { return previous == null ? value : Math.max(previous, value); }
    private static double indicator(boolean value) { return value ? 1.0 : 0.0; }
    private static double gain(int relevance) { return (1 << relevance) - 1; }
    private static double log2(int rank) { return Math.log(rank) / Math.log(2); }
    private static <T> List<T> first(List<T> values, int limit) { return values.subList(0, Math.min(values.size(), limit)); }
    private static int unknown(List<ScoredChunk> scores, Map<String, Judgment> labels) {
        return (int) scores.stream().filter(s -> !labels.containsKey(s.chunkId())).count();
    }

    private static void validateOptions(int topK, Double threshold) {
        if (topK < 1) throw new IllegalArgumentException("topK must be positive");
        if (threshold != null && !Double.isFinite(threshold)) {
            throw new IllegalArgumentException("Threshold must be finite, or null to disable filtering");
        }
    }

    private static List<QueryInput> checkedQueries(List<QueryInput> inputs) {
        List<QueryInput> queries = snapshot(inputs, "queries");
        requireUnique(queries, QueryInput::questionId, "questionId");
        return queries.stream().sorted(Comparator.comparing(QueryInput::questionId)).toList();
    }

    private static <T> List<T> snapshot(List<T> values, String name) {
        if (values == null || values.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException(name + " must be a nonnull list without null elements");
        }
        return List.copyOf(values);
    }

    private static <T> void requireUnique(List<T> values, Function<T, String> key, String name) {
        Set<String> seen = new HashSet<>();
        for (T value : values) {
            if (!seen.add(key.apply(value))) throw new IllegalArgumentException("Duplicate " + name + ": " + key.apply(value));
        }
    }

    private static void requireId(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
    }

    private static <K, V> Map<K, V> immutableMap(Map<K, V> values) {
        // Map.copyOf cannot represent the intentionally null metric values.
        return Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }
}
