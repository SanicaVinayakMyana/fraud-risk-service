package com.sanica.risk.engine

import static com.sanica.risk.support.Events.T0

import com.sanica.risk.api.Json
import com.sanica.risk.domain.Decision
import com.sanica.risk.features.InMemoryVelocityStore
import com.sanica.risk.features.RedisVelocityStore
import com.sanica.risk.ml.OnnxAnomalyModel
import com.sanica.risk.rules.RuleParser
import com.sanica.risk.rules.RuleRepository
import com.sanica.risk.sim.TrafficSimulator
import com.sanica.risk.support.TestRedis
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import spock.lang.Specification

import java.time.Clock
import java.time.Duration

/**
 * End-to-end scoring cost with the real ONNX model and all rules, excluding the Redis round trip
 * in the first test and including it in the second. Asserts generous bounds so CI noise cannot make it flaky; the printed
 * percentiles are the interesting part.
 */
class LatencySpec extends Specification {

    def "p99 scoring latency stays far inside the 50 ms budget"() {
        given:
        def model = OnnxAnomalyModel.loadDefault(Json.mapper())
        def engine = new RiskEngine(new InMemoryVelocityStore(), model,
                new RuleRepository(new RuleParser(Json.mapper()).parseClasspath('/rules.json')), ScoringPolicy.DEFAULT,
                new SimpleMeterRegistry(), Duration.ofMillis(50), Decision.REVIEW, Clock.systemUTC())
        def events = new TrafficSimulator(99).generate(400, T0, Duration.ofDays(3), 0.1)*.event()
        events.take(2_000).each { engine.score(it) }   // JIT warm-up

        when:
        def micros = events.drop(2_000).take(10_000).collect { engine.score(it).latencyMicros() }.sort()
        def p50 = micros[(int) (micros.size() * 0.50)]
        def p99 = micros[(int) (micros.size() * 0.99)]
        println "Scoring latency over ${micros.size()} transactions: p50 ${p50} us, p99 ${p99} us, max ${micros.last()} us"

        then:
        p99 < 5_000

        cleanup:
        engine?.close()
        model?.close()
    }

    def "with Redis the round trip dominates, and p99 still fits the budget"() {
        given:
        def redis = TestRedis.client()
        redis.flushAll()
        def model = OnnxAnomalyModel.loadDefault(Json.mapper())
        def engine = new RiskEngine(new RedisVelocityStore(redis, Duration.ofDays(1)), model,
                new RuleRepository(new RuleParser(Json.mapper()).parseClasspath('/rules.json')), ScoringPolicy.DEFAULT,
                new SimpleMeterRegistry(), Duration.ofMillis(50), Decision.REVIEW, Clock.systemUTC())
        def events = new TrafficSimulator(98).generate(200, T0, Duration.ofDays(3), 0.1)*.event()
        events.take(1_000).each { engine.score(it) }

        when:
        def decisions = events.drop(1_000).take(3_000).collect { engine.score(it) }
        def micros = decisions*.latencyMicros().sort()
        def p50 = micros[(int) (micros.size() * 0.50)]
        def p99 = micros[(int) (micros.size() * 0.99)]
        println "Scoring latency with Redis over ${micros.size()} transactions: p50 ${p50} us, p99 ${p99} us"

        then:
        decisions.count { it.degraded() } == 0
        p99 < 50_000

        cleanup:
        engine?.close()
        model?.close()
        redis?.close()
    }
}
