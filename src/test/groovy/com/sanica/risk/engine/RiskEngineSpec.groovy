package com.sanica.risk.engine

import static com.sanica.risk.support.Events.T0
import static com.sanica.risk.support.Events.history
import static com.sanica.risk.support.Events.tx

import com.sanica.risk.api.Json
import com.sanica.risk.domain.Decision
import com.sanica.risk.domain.TransactionEvent
import com.sanica.risk.features.InMemoryVelocityStore
import com.sanica.risk.features.VelocitySnapshot
import com.sanica.risk.features.VelocityStore
import com.sanica.risk.rules.RuleMode
import com.sanica.risk.rules.RuleParser
import com.sanica.risk.rules.RuleRepository
import com.sanica.risk.support.StubModel
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import spock.lang.AutoCleanup
import spock.lang.Specification

import java.time.Clock
import java.time.Duration
import java.time.ZoneOffset

class RiskEngineSpec extends Specification {

    def metrics = new SimpleMeterRegistry()
    def model = new StubModel()
    def rules = new RuleRepository(new RuleParser(Json.mapper()).parseClasspath('/rules.json'))
    def clock = Clock.fixed(T0, ZoneOffset.UTC)
    VelocityStore velocity = new InMemoryVelocityStore()

    @AutoCleanup
    RiskEngine engine = engine(velocity)

    RiskEngine engine(VelocityStore store, Duration budget = Duration.ofMillis(200)) {
        new RiskEngine(store, model, rules, ScoringPolicy.DEFAULT, metrics, budget, Decision.REVIEW, clock)
    }

    def "an everyday payment is allowed with LOW_RISK"() {
        given:
        history('acct-1', 10).each { velocity.record(it) }

        when:
        def d = engine.score(tx())

        then:
        d.decision() == Decision.ALLOW
        d.primaryReason() == 'LOW_RISK'
        !d.degraded()
        d.modelVersion() == 'stub-model'
        d.rulesVersion() == '2026-10-25.1'
        d.decidedAt() == T0
        d.latencyMicros() >= 0
    }

    def "card testing: the burst is blocked with CARD_TESTING as the primary reason"() {
        given:
        def burst = (0..<9).collect { tx(amount: '1.50', at: T0.plusSeconds(it * 3)) }

        when:
        def decisions = burst.collect { engine.score(it) }

        then: 'the first five pass, the sixth trips the 60s velocity rule, the eighth the card-testing block'
        decisions[0..4]*.decision().every { it == Decision.ALLOW }
        decisions[5].reasons()*.code().contains('VELOCITY_60S')
        decisions[7].decision() == Decision.BLOCK
        decisions[7].primaryReason() == 'CARD_TESTING'
        decisions[7].reasons().find { it.code() == 'CARD_TESTING' }.detail().contains('txCount60s=8')
    }

    def "new device plus high amount goes to review"() {
        given: 'an account that usually spends around 800'
        history('acct-1', 10, T0, '800.00').each { velocity.record(it) }

        when:
        def d = engine.score(tx(device: 'new-phone', amount: '1000'))

        then:
        d.decision() == Decision.REVIEW
        d.score() == 55
        d.reasons()*.code() == ['NEW_DEVICE_HIGH_AMOUNT']
    }

    def "new device plus an amount far above the account's usual spend is blocked"() {
        given:
        history('acct-1', 10).each { velocity.record(it) }

        when:
        def d = engine.score(tx(device: 'new-phone', amount: '1500'))

        then: '55 + 30 points'
        d.decision() == Decision.BLOCK
        d.score() == 85
        d.reasons()*.code() == ['NEW_DEVICE_HIGH_AMOUNT', 'AMOUNT_SPIKE']
    }

    def "the model's score is part of the decision and can push it over a threshold"() {
        given:
        history('acct-1', 10, T0, '800.00').each { velocity.record(it) }
        model.scorer = { f -> 0.95d }

        when:
        def d = engine.score(tx(device: 'new-phone', amount: '1000'))

        then:
        d.decision() == Decision.BLOCK
        d.mlScore() == 0.95d
        d.reasons()*.code().contains('ML_ANOMALY')
    }

    def "shadow rules are evaluated and reported but never change the live decision"() {
        given: 'a brand-new account making a large first payment matches the FIRST_TX_HIGH_AMOUNT shadow rule'
        def event = tx(account: 'fresh', amount: '3000')

        when:
        def d = engine.score(event)

        then:
        d.decision() == Decision.ALLOW
        !d.reasons()*.code().contains('FIRST_TX_HIGH_AMOUNT')
        d.shadow().decision() == Decision.REVIEW
        d.shadow().reasons()*.code().contains('FIRST_TX_HIGH_AMOUNT')
        d.shadow().differsFrom(d.decision())

        and: 'the shadow report counts what enforcing the rule would have done'
        def report = engine.shadowReport()
        report.evaluated() == 1
        report.divergentDecisions() == 1
        with(report.rules().find { it.ruleId() == 'FIRST_TX_HIGH_AMOUNT' }) {
            hits() == 1
            wouldChangeDecision() == 1
            changes() == ['ALLOW->REVIEW': 1L]
        }
        metrics.counter('risk.shadow.hits', 'rule', 'FIRST_TX_HIGH_AMOUNT').count() == 1
    }

    def "promoting the shadow rule makes it enforce"() {
        given:
        rules.setMode('FIRST_TX_HIGH_AMOUNT', RuleMode.ENFORCE)

        when:
        def d = engine.score(tx(account: 'fresh', amount: '3000'))

        then:
        d.decision() == Decision.REVIEW
        d.primaryReason() == 'FIRST_TX_HIGH_AMOUNT'
        d.shadow() == null || !d.shadow().differsFrom(d.decision())
        d.rulesVersion() == '2026-10-25.1-r1'
    }

    def "hard limits block outright"() {
        expect:
        engine.score(tx(amount: '30000')).decision() == Decision.BLOCK
    }

    def "a slow velocity store cannot break the latency budget: the fallback decision is returned in time"() {
        given:
        def slow = { TransactionEvent e -> Thread.sleep(500); null } as VelocityStore
        def fast = engine(slow, Duration.ofMillis(30))

        when:
        long start = System.nanoTime()
        def d = fast.score(tx())
        long elapsedMs = (System.nanoTime() - start).intdiv(1_000_000)

        then:
        elapsedMs < 300
        d.decision() == Decision.REVIEW
        d.degraded()
        d.primaryReason() == RiskEngine.LATENCY_BUDGET_EXCEEDED
        metrics.counter('risk.decisions', 'decision', 'REVIEW', 'degraded', 'true').count() == 1

        cleanup:
        fast.close()
    }

    def "a failing velocity store degrades to the fallback decision"() {
        given:
        def broken = { TransactionEvent e -> throw new IllegalStateException('Redis down') } as VelocityStore
        def degraded = engine(broken)

        when:
        def d = degraded.score(tx())

        then:
        d.degraded()
        d.decision() == Decision.REVIEW
        d.primaryReason() == RiskEngine.FEATURES_UNAVAILABLE
        d.reasons()[0].detail().contains('Redis down')

        cleanup:
        degraded.close()
    }

    def "if the model fails, the rules still decide and the decision is flagged"() {
        given:
        model.scorer = { f -> throw new IllegalStateException('onnx') }

        when:
        def d = engine.score(tx(amount: '30000'))

        then:
        d.decision() == Decision.BLOCK
        d.degraded()
        d.reasons()*.code() == ['HARD_AMOUNT_LIMIT', RiskEngine.MODEL_UNAVAILABLE]
    }

    def "latency and decisions are measured"() {
        when:
        3.times { engine.score(tx()) }

        then:
        metrics.timer('risk.scoring.latency').count() == 3
        metrics.counter('risk.decisions', 'decision', 'ALLOW', 'degraded', 'false').count() == 3
    }
}
