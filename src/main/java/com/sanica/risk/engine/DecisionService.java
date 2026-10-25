package com.sanica.risk.engine;

import com.sanica.risk.domain.RiskDecision;
import com.sanica.risk.domain.TransactionEvent;
import java.util.function.Consumer;

/**
 * The one path every transaction takes, whether it arrives over HTTP or from the event stream:
 * validate, return the stored decision if this transaction was already scored, otherwise score, store, publish.
 */
public final class DecisionService {

    private final RiskEngine engine;
    private final DecisionStore store;
    private final Consumer<RiskDecision> publisher;

    public DecisionService(RiskEngine engine, DecisionStore store, Consumer<RiskDecision> publisher) {
        this.engine = engine;
        this.store = store;
        this.publisher = publisher;
    }

    public RiskDecision decide(TransactionEvent event) {
        event.validate();
        var existing = store.find(event.transactionId());
        if (existing.isPresent()) {
            return existing.get();
        }
        RiskDecision decision = engine.score(event);
        RiskDecision stored = store.saveIfAbsent(decision);
        if (stored == decision) {
            publisher.accept(decision);
        }
        return stored;
    }
}
