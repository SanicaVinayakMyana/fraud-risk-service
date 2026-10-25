package com.sanica.risk.domain;

import java.util.List;

/**
 * What the decision would have been if the shadow rules were enforced. Recorded next to every live decision
 * so a new rule can be judged on production traffic before it is allowed to block anyone.
 */
public record ShadowOutcome(Decision decision, int score, List<Reason> reasons) {

    public boolean differsFrom(Decision live) {
        return decision != live;
    }
}
