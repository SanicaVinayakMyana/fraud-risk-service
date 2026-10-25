package com.sanica.risk.engine;

import com.sanica.risk.domain.Decision;
import com.sanica.risk.domain.Reason;
import com.sanica.risk.rules.RuleSet.Hit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Combines the model score and rule hits into one decision. Pure and deterministic: the same inputs always give
 * the same output, which is what lets shadow mode compute "what would have happened" exactly.
 */
public final class DecisionMaker {

    public static final String LOW_RISK = "LOW_RISK";
    public static final String ML_ANOMALY = "ML_ANOMALY";

    private final ScoringPolicy policy;

    public DecisionMaker(ScoringPolicy policy) {
        this.policy = policy;
    }

    public record Outcome(Decision decision, int score, List<Reason> reasons) {
    }

    public Outcome decide(double mlScore, List<Hit> hits) {
        List<Reason> reasons = new ArrayList<>();
        Decision floor = Decision.ALLOW;
        int points = 0;

        if (mlScore >= policy.mlReasonThreshold()) {
            int mlPoints = (int) Math.round(policy.mlWeight() * mlScore);
            points += mlPoints;
            reasons.add(new Reason(ML_ANOMALY, mlPoints,
                    String.format("Isolation Forest anomaly score %.2f", mlScore)));
        }
        List<Hit> ordered = hits.stream()
                .sorted(Comparator.comparing((Hit h) -> h.rule().action() == null ? Decision.ALLOW : h.rule().action())
                        .reversed()
                        .thenComparing(h -> -h.rule().points()))
                .toList();
        List<Reason> ruleReasons = new ArrayList<>();
        for (Hit hit : ordered) {
            points += hit.rule().points();
            ruleReasons.add(hit.reason());
            if (hit.rule().action() != null) {
                floor = Decision.max(floor, hit.rule().action());
            }
        }

        int score = Math.min(100, points);
        Decision byScore = score >= policy.blockThreshold() ? Decision.BLOCK
                : score >= policy.reviewThreshold() ? Decision.REVIEW
                : Decision.ALLOW;
        Decision decision = Decision.max(byScore, floor);

        // Rules that force an action explain the decision best, then the largest contributors.
        List<Reason> all = new ArrayList<>(ruleReasons);
        all.addAll(reasons);
        all.sort(Comparator.comparing((Reason r) -> !isForcing(r, ordered)).thenComparing(r -> -r.points()));
        if (all.isEmpty()) {
            all.add(new Reason(LOW_RISK, 0, "No rule matched and the transaction looks typical for the account"));
        }
        return new Outcome(decision, score, List.copyOf(all));
    }

    private static boolean isForcing(Reason reason, List<Hit> hits) {
        return hits.stream().anyMatch(h -> h.reason() == reason && h.rule().action() != null);
    }
}
