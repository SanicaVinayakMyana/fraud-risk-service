package com.sanica.risk.engine;

import com.sanica.risk.domain.RiskDecision;
import java.util.List;
import java.util.Optional;

/** Decisions by transaction id. Makes scoring idempotent: a redelivered event gets its original decision back. */
public interface DecisionStore {

    /** Stores the decision unless one already exists for the transaction; returns the one that is stored. */
    RiskDecision saveIfAbsent(RiskDecision decision);

    Optional<RiskDecision> find(String transactionId);

    /** Newest first. */
    List<RiskDecision> recent(int limit);
}
