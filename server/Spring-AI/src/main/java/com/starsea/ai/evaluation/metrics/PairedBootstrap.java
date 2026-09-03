package com.starsea.ai.evaluation.metrics;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.starsea.ai.evaluation.metrics.EvaluationMetrics.*;

/** Internal intent-cluster resampling; no unpaired resampling or pseudo-independent variants. */
final class PairedBootstrap {
    private static final int ITERATIONS = 10_000;
    private static final long SEED = 20260904L;

    private PairedBootstrap() {}

    static Comparison compare(List<QueryInput> baseline, List<QueryInput> candidate, int topK) {
        Map<String, QueryInput> byId = candidate.stream().collect(Collectors.toMap(QueryInput::questionId, Function.identity()));
        List<Pair> pairs = new ArrayList<>();
        for (QueryInput base : baseline) {
            QueryInput other = byId.get(base.questionId());
            if (other == null) continue;
            validatePair(base, other);
            pairs.add(new Pair(base, analyze(base, topK, null), analyze(other, topK, null)));
        }
        Map<String, MetricComparison> metrics = new LinkedHashMap<>();
        for (String metric : metricNames()) {
            Map<String, List<Observation>> grouped = new TreeMap<>();
            for (Pair pair : pairs) {
                Double base = pair.baselineValues.get(metric);
                Double other = pair.candidateValues.get(metric);
                if (base == null || other == null) continue;
                grouped.computeIfAbsent(groupKey(pair.input), key -> new ArrayList<>()).add(new Observation(base, other, 1));
            }
            metrics.put(metric, estimate(grouped, metric));
        }
        Map<String, List<Pair>> variants = new TreeMap<>();
        for (Pair pair : pairs) {
            if (pair.input.reviewed() && pair.input.answerable()) {
                variants.computeIfAbsent(groupKey(pair.input), key -> new ArrayList<>()).add(pair);
            }
        }
        Map<String, List<Observation>> variantObservations = new TreeMap<>();
        for (var entry : variants.entrySet()) {
            List<Pair> group = entry.getValue();
            if (group.size() < 2) continue;
            Double base = allHit(group.stream().map(p -> p.baseline).toList());
            Double other = allHit(group.stream().map(p -> p.candidate).toList());
            if (base != null && other != null) {
                variantObservations.put(entry.getKey(), List.of(new Observation(base, other, group.size())));
            }
        }
        metrics.put("variantGroupHitRate", estimate(variantObservations, "variantGroupHitRate"));
        int groupCount = (int) pairs.stream().map(p -> groupKey(p.input)).distinct().count();
        return new Comparison(baseline.size(), candidate.size(), pairs.size(), baseline.size() - pairs.size(),
                candidate.size() - pairs.size(), groupCount, ITERATIONS, SEED, .95, metrics);
    }

    private static MetricComparison estimate(Map<String, List<Observation>> groups, String metric) {
        List<Double> baseMeans = new ArrayList<>(), candidateMeans = new ArrayList<>(), deltas = new ArrayList<>();
        int questionCount = 0;
        for (List<Observation> observations : groups.values()) {
            double base = observations.stream().mapToDouble(Observation::baseline).average().orElseThrow();
            double candidate = observations.stream().mapToDouble(Observation::candidate).average().orElseThrow();
            baseMeans.add(base);
            candidateMeans.add(candidate);
            deltas.add(candidate - base);
            questionCount += observations.stream().mapToInt(Observation::questions).sum();
        }
        Double delta = mean(deltas);
        Double low = null, high = null;
        String evidence = "INSUFFICIENT";
        if (groups.size() >= 2) {
            Random random = new Random(SEED);
            List<Double> estimates = new ArrayList<>(ITERATIONS);
            for (int iteration = 0; iteration < ITERATIONS; iteration++) {
                double sum = 0;
                for (int i = 0; i < deltas.size(); i++) sum += deltas.get(random.nextInt(deltas.size()));
                estimates.add(sum / deltas.size());
            }
            low = percentile(estimates, .025);
            high = percentile(estimates, .975);
            boolean lowerIsBetter = List.of("noAnswerFalsePositiveRate", "answerableEmptyRate", "latencyMs", "failureRate").contains(metric);
            evidence = low > 0 ? (lowerIsBetter ? "REGRESSED" : "IMPROVED")
                    : high < 0 ? (lowerIsBetter ? "IMPROVED" : "REGRESSED") : "INCONCLUSIVE";
        }
        return new MetricComparison(mean(baseMeans), mean(candidateMeans), delta, low, high,
                questionCount, groups.size(), evidence);
    }

    private static void validatePair(QueryInput base, QueryInput candidate) {
        if (!groupKey(base).equals(groupKey(candidate)) || !Objects.equals(base.category(), candidate.category())
                || !Objects.equals(base.split(), candidate.split()) || base.answerable() != candidate.answerable()
                || base.reviewed() != candidate.reviewed()
                || !new HashSet<>(base.judgments()).equals(new HashSet<>(candidate.judgments()))) {
            throw new IllegalArgumentException("Paired questions must use identical metadata and frozen judgments: " + base.questionId());
        }
    }

    private record Observation(double baseline, double candidate, int questions) {}

    private static final class Pair {
        final QueryInput input;
        final CaseMetrics baseline, candidate;
        final Map<String, Double> baselineValues, candidateValues;

        Pair(QueryInput input, CaseMetrics baseline, CaseMetrics candidate) {
            this.input = input;
            this.baseline = baseline;
            this.candidate = candidate;
            baselineValues = values(baseline);
            candidateValues = values(candidate);
        }
    }
}
