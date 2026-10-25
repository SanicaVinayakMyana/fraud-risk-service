package com.sanica.risk.engine;

import com.sanica.risk.domain.Decision;
import com.sanica.risk.domain.TransactionEvent;
import com.sanica.risk.rules.RuleSet.Hit;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tracks what shadow rules would have done. For each shadow rule it counts how often it matched and how often
 * enforcing it <em>alone</em> would have changed the live decision, which is the number a risk team needs before
 * promoting a rule: "it fires on 2% of traffic and would have sent 0.4% to review".
 */
public final class ShadowMonitor {

    private static final Logger log = LoggerFactory.getLogger("shadow");

    private final DecisionMaker decisionMaker;
    private final MeterRegistry metrics;
    private final LongAdder evaluated = new LongAdder();
    private final LongAdder divergent = new LongAdder();
    private final Map<String, RuleStats> byRule = new ConcurrentHashMap<>();

    public ShadowMonitor(DecisionMaker decisionMaker, MeterRegistry metrics) {
        this.decisionMaker = decisionMaker;
        this.metrics = metrics;
    }

    /** Called once per scored transaction, with the live decision already made. */
    public void record(TransactionEvent event, double mlScore, List<Hit> enforced, Decision live,
                       List<Hit> shadowHits, Decision shadowDecision) {
        evaluated.increment();
        if (shadowHits.isEmpty()) {
            return;
        }
        if (shadowDecision != live) {
            divergent.increment();
        }
        for (Hit hit : shadowHits) {
            List<Hit> withThisRule = new ArrayList<>(enforced);
            withThisRule.add(hit);
            Decision alone = decisionMaker.decide(mlScore, withThisRule).decision();
            RuleStats stats = byRule.computeIfAbsent(hit.rule().id(), id -> new RuleStats());
            stats.hits.increment();
            metrics.counter("risk.shadow.hits", "rule", hit.rule().id()).increment();
            if (alone != live) {
                stats.changes.computeIfAbsent(live + "->" + alone, k -> new LongAdder()).increment();
                metrics.counter("risk.shadow.decision.changes", "rule", hit.rule().id(),
                        "from", live.name(), "to", alone.name()).increment();
                log.info("rule={} tx={} account={} live={} wouldBe={} evidence=\"{}\"", hit.rule().id(),
                        event.transactionId(), event.accountId(), live, alone, hit.reason().detail());
            }
        }
    }

    public record RuleReport(String ruleId, long hits, double hitRate, long wouldChangeDecision,
                             Map<String, Long> changes) {
    }

    public record Report(long evaluated, long divergentDecisions, List<RuleReport> rules) {
    }

    public Report report() {
        long total = evaluated.sum();
        List<RuleReport> rules = byRule.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> {
                    long hits = e.getValue().hits.sum();
                    Map<String, Long> changes = new java.util.TreeMap<>();
                    e.getValue().changes.forEach((k, v) -> changes.put(k, v.sum()));
                    long changed = changes.values().stream().mapToLong(Long::longValue).sum();
                    return new RuleReport(e.getKey(), hits, total == 0 ? 0 : (double) hits / total, changed, changes);
                })
                .toList();
        return new Report(total, divergent.sum(), rules);
    }

    private static final class RuleStats {
        final LongAdder hits = new LongAdder();
        final Map<String, LongAdder> changes = new ConcurrentHashMap<>();
    }
}
