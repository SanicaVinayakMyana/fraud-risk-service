package com.sanica.risk.rules;

import com.sanica.risk.domain.Decision;

/**
 * One velocity or pattern rule. When {@code when} matches, the rule adds {@code points} to the risk score and,
 * if {@code action} is set, raises the decision to at least that action regardless of the score.
 */
public record Rule(
        String id,
        String description,
        String reasonCode,
        int points,
        Decision action,
        RuleMode mode,
        Condition when) {

    public Rule withMode(RuleMode newMode) {
        return new Rule(id, description, reasonCode, points, action, newMode, when);
    }
}
