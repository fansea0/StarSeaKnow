package com.starsea.ai.evaluation.metrics;

/** Numerically stable, dependency-free operations on embedding vectors. */
public final class EvaluationMath {
    private EvaluationMath() {}

    /**
     * Returns signed cosine similarity. Each vector is scaled independently before accumulating
     * its norm, so even finite subnormal or very large coordinates remain usable.
     *
     * @throws IllegalArgumentException for null, empty, dimension-mismatched, nonfinite or zero vectors
     */
    public static double cosine(double[] left, double[] right) {
        if (left == null || right == null || left.length == 0 || left.length != right.length) {
            throw new IllegalArgumentException("Vectors must be nonempty and have equal dimensions");
        }
        double leftScale = scale(left);
        double rightScale = scale(right);
        Sum dot = new Sum();
        Sum leftNorm = new Sum();
        Sum rightNorm = new Sum();
        for (int i = 0; i < left.length; i++) {
            double a = left[i] / leftScale;
            double b = right[i] / rightScale;
            dot.add(a * b);
            leftNorm.add(a * a);
            rightNorm.add(b * b);
        }
        double result = dot.value / Math.sqrt(leftNorm.value) / Math.sqrt(rightNorm.value);
        // Correct floating point round-off at the mathematical endpoints, preserving negatives.
        return Math.max(-1.0, Math.min(1.0, result));
    }

    private static double scale(double[] vector) {
        double scale = 0;
        for (double coordinate : vector) {
            if (!Double.isFinite(coordinate)) {
                throw new IllegalArgumentException("Vector coordinates must be finite");
            }
            scale = Math.max(scale, Math.abs(coordinate));
        }
        if (scale == 0) {
            throw new IllegalArgumentException("Vector norm must be nonzero");
        }
        return scale;
    }

    private static final class Sum {
        private double value;
        private double correction;

        void add(double term) {
            double adjusted = term - correction;
            double next = value + adjusted;
            correction = (next - value) - adjusted;
            value = next;
        }
    }
}
