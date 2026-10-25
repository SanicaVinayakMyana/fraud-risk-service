package com.sanica.risk.rules;

import com.sanica.risk.domain.Reason;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/** An immutable, versioned set of rules. Every decision records the version it was made with. */
public record RuleSet(String version, List<Rule> rules) {

    public RuleSet {
        rules = List.copyOf(rules);
    }

    /** Hits split by mode: enforced hits drive the decision, shadow hits only the shadow outcome. */
    public record Evaluation(List<Hit> enforced, List<Hit> shadow) {
    }

    public record Hit(Rule rule, Reason reason) {
    }

    public Evaluation evaluate(RuleContext ctx) {
        List<Hit> enforced = new ArrayList<>();
        List<Hit> shadow = new ArrayList<>();
        for (Rule rule : rules) {
            if (rule.mode() == RuleMode.DISABLED || !rule.when().test(ctx)) {
                continue;
            }
            String evidence = rule.when().fields().stream().map(ctx::describe).collect(Collectors.joining(", "));
            Hit hit = new Hit(rule, new Reason(rule.reasonCode(), rule.points(), rule.description() + " (" + evidence + ")"));
            (rule.mode() == RuleMode.ENFORCE ? enforced : shadow).add(hit);
        }
        return new Evaluation(enforced, shadow);
    }

    public Rule find(String id) {
        return rules.stream().filter(r -> r.id().equals(id)).findFirst().orElse(null);
    }
}
