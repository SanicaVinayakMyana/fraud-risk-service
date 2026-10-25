package com.sanica.risk.domain;

import java.time.Instant;
import java.util.List;

/**
 * The service's answer for one transaction. {@code reasons} is never empty: an ALLOW carries
 * {@code LOW_RISK}, a fallback carries the cause of the degradation.
 */
public record RiskDecision(
        String transactionId,
        String accountId,
        Decision decision,
        int score,
        List<Reason> reasons,
        double mlScore,
        ShadowOutcome shadow,
        boolean degraded,
        String modelVersion,
        String rulesVersion,
        long latencyMicros,
        Instant decidedAt) {

    public String primaryReason() {
        return reasons.get(0).code();
    }
}
