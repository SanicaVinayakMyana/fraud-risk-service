package com.sanica.risk.engine;

import com.sanica.risk.domain.Decision;
import com.sanica.risk.domain.Reason;
import com.sanica.risk.domain.RiskDecision;
import com.sanica.risk.domain.ShadowOutcome;
import com.sanica.risk.domain.TransactionEvent;
import com.sanica.risk.features.Features;
import com.sanica.risk.features.VelocitySnapshot;
import com.sanica.risk.features.VelocityStore;
import com.sanica.risk.ml.AnomalyModel;
import com.sanica.risk.rules.RuleContext;
import com.sanica.risk.rules.RuleRepository;
import com.sanica.risk.rules.RuleSet;
import com.sanica.risk.rules.RuleSet.Hit;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Scores one transaction: velocity features from Redis, anomaly score from the ONNX model, rules on top, one
 * decision with reason codes. The whole evaluation runs against a latency budget; if Redis is slow or down the
 * caller still gets an answer in time, the configured fallback decision, marked {@code degraded}.
 */
public final class RiskEngine implements AutoCloseable {

    public static final String LATENCY_BUDGET_EXCEEDED = "LATENCY_BUDGET_EXCEEDED";
    public static final String FEATURES_UNAVAILABLE = "FEATURES_UNAVAILABLE";
    public static final String MODEL_UNAVAILABLE = "MODEL_UNAVAILABLE";

    private static final Logger log = LoggerFactory.getLogger(RiskEngine.class);

    private final VelocityStore velocity;
    private final AnomalyModel model;
    private final RuleRepository rules;
    private final DecisionMaker decisionMaker;
    private final ShadowMonitor shadow;
    private final MeterRegistry metrics;
    private final Duration budget;
    private final Decision fallback;
    private final Clock clock;
    private final Timer latency;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public RiskEngine(VelocityStore velocity, AnomalyModel model, RuleRepository rules, ScoringPolicy policy,
                      MeterRegistry metrics, Duration budget, Decision fallback, Clock clock) {
        this.velocity = velocity;
        this.model = model;
        this.rules = rules;
        this.decisionMaker = new DecisionMaker(policy);
        this.shadow = new ShadowMonitor(decisionMaker, metrics);
        this.metrics = metrics;
        this.budget = budget;
        this.fallback = fallback;
        this.clock = clock;
        this.latency = Timer.builder("risk.scoring.latency")
                .description("Time to produce a decision, including Redis and model inference")
                .publishPercentiles(0.5, 0.95, 0.99)
                .publishPercentileHistogram()
                .serviceLevelObjectives(budget)
                .register(metrics);
    }

    public RiskDecision score(TransactionEvent event) {
        long start = System.nanoTime();
        Future<RiskDecision> task = executor.submit(() -> evaluate(event, start));
        RiskDecision decision;
        try {
            decision = task.get(budget.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            task.cancel(true);
            decision = degraded(event, start, LATENCY_BUDGET_EXCEEDED,
                    "No decision within the " + budget.toMillis() + " ms budget");
        } catch (ExecutionException e) {
            log.warn("Scoring {} failed, falling back to {}", event.transactionId(), fallback, e.getCause());
            decision = degraded(event, start, FEATURES_UNAVAILABLE, "Velocity store error: " + e.getCause().getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            decision = degraded(event, start, LATENCY_BUDGET_EXCEEDED, "Interrupted while scoring");
        }
        latency.record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
        metrics.counter("risk.decisions", "decision", decision.decision().name(),
                "degraded", Boolean.toString(decision.degraded())).increment();
        return decision;
    }

    private RiskDecision evaluate(TransactionEvent event, long start) {
        VelocitySnapshot snapshot = velocity.record(event);
        Features features = Features.of(event, snapshot);

        boolean modelDown = false;
        double mlScore;
        try {
            mlScore = model.score(features);
        } catch (RuntimeException e) {
            // Rules alone still give a sound decision; flag it rather than fail the payment.
            log.warn("Model inference failed for {}", event.transactionId(), e);
            mlScore = 0;
            modelDown = true;
        }

        RuleSet ruleSet = rules.current();
        RuleSet.Evaluation evaluation = ruleSet.evaluate(new RuleContext(event, features, mlScore));
        DecisionMaker.Outcome live = decisionMaker.decide(mlScore, evaluation.enforced());
        evaluation.enforced().forEach(hit -> metrics.counter("risk.rule.hits", "rule", hit.rule().id()).increment());

        ShadowOutcome shadowOutcome = null;
        if (!evaluation.shadow().isEmpty()) {
            List<Hit> all = new ArrayList<>(evaluation.enforced());
            all.addAll(evaluation.shadow());
            DecisionMaker.Outcome would = decisionMaker.decide(mlScore, all);
            shadowOutcome = new ShadowOutcome(would.decision(), would.score(), would.reasons());
        }
        shadow.record(event, mlScore, evaluation.enforced(), live.decision(), evaluation.shadow(),
                shadowOutcome == null ? live.decision() : shadowOutcome.decision());

        List<Reason> reasons = live.reasons();
        if (modelDown) {
            reasons = new ArrayList<>(reasons);
            reasons.add(new Reason(MODEL_UNAVAILABLE, 0, "Decided on rules only; model inference failed"));
        }
        return new RiskDecision(event.transactionId(), event.accountId(), live.decision(), live.score(),
                List.copyOf(reasons), round(mlScore), shadowOutcome, modelDown, model.version(), ruleSet.version(),
                micros(start), clock.instant());
    }

    private RiskDecision degraded(TransactionEvent event, long start, String code, String detail) {
        return new RiskDecision(event.transactionId(), event.accountId(), fallback, 0,
                List.of(new Reason(code, 0, detail + "; applied fallback " + fallback)), 0, null, true,
                model.version(), rules.current().version(), micros(start), clock.instant());
    }

    public ShadowMonitor.Report shadowReport() {
        return shadow.report();
    }

    public String modelVersion() {
        return model.version();
    }

    private static long micros(long start) {
        return (System.nanoTime() - start) / 1_000;
    }

    private static double round(double value) {
        return Math.round(value * 10_000) / 10_000.0;
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
