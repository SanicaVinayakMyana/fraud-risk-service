package com.sanica.risk.engine;

/**
 * How rule points and the model's score become one 0-100 risk score and a decision.
 *
 * @param reviewThreshold    score at or above which a transaction goes to manual review
 * @param blockThreshold     score at or above which it is blocked
 * @param mlWeight           points the model contributes at an anomaly score of 1.0
 * @param mlReasonThreshold  model scores below this contribute nothing, so every point in a score has a reason
 */
public record ScoringPolicy(int reviewThreshold, int blockThreshold, int mlWeight, double mlReasonThreshold) {

    public static final ScoringPolicy DEFAULT = new ScoringPolicy(50, 80, 40, 0.5);

    public ScoringPolicy {
        if (reviewThreshold <= 0 || blockThreshold <= reviewThreshold || blockThreshold > 100) {
            throw new IllegalArgumentException("Need 0 < reviewThreshold < blockThreshold <= 100");
        }
        if (mlWeight < 0 || mlWeight > 100) {
            throw new IllegalArgumentException("mlWeight must be between 0 and 100");
        }
    }
}
