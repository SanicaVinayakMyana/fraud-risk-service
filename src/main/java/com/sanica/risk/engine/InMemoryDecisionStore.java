package com.sanica.risk.engine;

import com.sanica.risk.domain.RiskDecision;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Bounded store for local runs and tests: the oldest decisions are evicted first. */
public final class InMemoryDecisionStore implements DecisionStore {

    private final Map<String, RiskDecision> decisions;

    public InMemoryDecisionStore(int capacity) {
        this.decisions = new LinkedHashMap<>(16, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, RiskDecision> eldest) {
                return size() > capacity;
            }
        };
    }

    @Override
    public synchronized RiskDecision saveIfAbsent(RiskDecision decision) {
        RiskDecision existing = decisions.putIfAbsent(decision.transactionId(), decision);
        return existing == null ? decision : existing;
    }

    @Override
    public synchronized Optional<RiskDecision> find(String transactionId) {
        return Optional.ofNullable(decisions.get(transactionId));
    }

    @Override
    public synchronized List<RiskDecision> recent(int limit) {
        List<RiskDecision> all = new ArrayList<>(decisions.values());
        List<RiskDecision> newest = new ArrayList<>(Math.min(limit, all.size()));
        for (int i = all.size() - 1; i >= 0 && newest.size() < limit; i--) {
            newest.add(all.get(i));
        }
        return newest;
    }
}
