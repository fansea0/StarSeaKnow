package com.starsea.ai.evaluation.metrics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EvaluationMathTest {
    @Test
    void cosine_preserves_negative_orthogonal_and_hand_computable_scores() {
        assertEquals(1.0, EvaluationMath.cosine(new double[]{3, 4}, new double[]{6, 8}), 1e-14);
        assertEquals(-1.0, EvaluationMath.cosine(new double[]{3, 4}, new double[]{-6, -8}), 1e-14);
        assertEquals(0.0, EvaluationMath.cosine(new double[]{1, 0}, new double[]{0, 1}), 1e-14);
        assertEquals(0.6, EvaluationMath.cosine(new double[]{3, 4}, new double[]{1, 0}), 1e-14);
    }

    @Test
    void cosine_handles_finite_vectors_whose_naive_norm_overflows_or_underflows() {
        assertEquals(1.0, EvaluationMath.cosine(
                new double[]{Double.MAX_VALUE, Double.MAX_VALUE},
                new double[]{Double.MIN_VALUE, Double.MIN_VALUE}), 1e-14);
        assertEquals(0.0, EvaluationMath.cosine(new double[]{1e300, 1e300},
                new double[]{1e-300, -1e-300}), 1e-14);
    }

    @Test
    void cosine_rejects_missing_empty_mismatched_zero_and_nonfinite_vectors() {
        double[] valid = {1, 2};
        assertThrows(IllegalArgumentException.class, () -> EvaluationMath.cosine(null, valid));
        assertThrows(IllegalArgumentException.class, () -> EvaluationMath.cosine(valid, null));
        assertThrows(IllegalArgumentException.class, () -> EvaluationMath.cosine(new double[0], new double[0]));
        assertThrows(IllegalArgumentException.class, () -> EvaluationMath.cosine(valid, new double[]{1}));
        assertThrows(IllegalArgumentException.class, () -> EvaluationMath.cosine(valid, new double[]{0, -0.0}));
        assertThrows(IllegalArgumentException.class, () -> EvaluationMath.cosine(new double[]{Double.NaN, 1}, valid));
        assertThrows(IllegalArgumentException.class, () -> EvaluationMath.cosine(valid, new double[]{Double.POSITIVE_INFINITY, 1}));
        assertThrows(IllegalArgumentException.class, () -> EvaluationMath.cosine(new double[]{Double.NEGATIVE_INFINITY, 1}, valid));
    }
}
