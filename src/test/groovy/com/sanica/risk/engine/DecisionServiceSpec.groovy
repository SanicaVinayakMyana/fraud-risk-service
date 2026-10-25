package com.sanica.risk.engine

import static com.sanica.risk.support.Events.T0
import static com.sanica.risk.support.Events.tx

import com.sanica.risk.api.Json
import com.sanica.risk.domain.Decision
import com.sanica.risk.domain.Reason
import com.sanica.risk.domain.RiskDecision
import com.sanica.risk.domain.ValidationException
import com.sanica.risk.features.InMemoryVelocityStore
import com.sanica.risk.rules.RuleParser
import com.sanica.risk.rules.RuleRepository
import com.sanica.risk.support.StubModel
import com.sanica.risk.support.TestRedis
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import spock.lang.AutoCleanup
import spock.lang.Specification

import java.time.Clock
import java.time.Duration

class DecisionServiceSpec extends Specification {

    @AutoCleanup
    def engine = new RiskEngine(new InMemoryVelocityStore(), new StubModel(),
            new RuleRepository(new RuleParser(Json.mapper()).parseClasspath('/rules.json')), ScoringPolicy.DEFAULT,
            new SimpleMeterRegistry(), Duration.ofMillis(200), Decision.REVIEW, Clock.systemUTC())
    def published = []
    def store = new InMemoryDecisionStore(100)
    def service = new DecisionService(engine, store, { published << it })

    static RiskDecision decision(String id) {
        new RiskDecision(id, 'acct', Decision.ALLOW, 0, [new Reason('LOW_RISK', 0, '')], 0.1d, null, false, 'm', 'r',
                10, T0)
    }

    def "a redelivered transaction gets its original decision and is published only once"() {
        given:
        def event = tx(id: 'tx-1')

        when:
        def first = service.decide(event)
        def second = service.decide(event)

        then:
        second == first
        published == [first]
        store.find('tx-1').get() == first
    }

    def "invalid events are rejected before scoring: #problem"() {
        when:
        service.decide(event)

        then:
        def e = thrown(ValidationException)
        e.message.contains(message)
        published.isEmpty()

        where:
        problem           | event                         || message
        'no id'           | tx(id: ' ')                   || 'transactionId'
        'no account'      | tx(account: null)             || 'accountId'
        'zero amount'     | tx(amount: '0')               || 'amount must be positive'
        'unknown currency'| tx(currency: 'XYZ')           || 'currency must be one of'
        'no channel'      | tx(channel: null)             || 'channel is required'
        'no timestamp'    | tx(at: null)                  || 'timestamp is required'
        'bad country'     | tx(country: 'usa')            || 'ISO 3166-1'
    }

    def "the in-memory store returns newest first and evicts the oldest"() {
        given:
        def small = new InMemoryDecisionStore(3)
        (1..5).each { small.saveIfAbsent(decision("tx-$it")) }

        expect:
        small.recent(10)*.transactionId() == ['tx-5', 'tx-4', 'tx-3']
        small.recent(2)*.transactionId() == ['tx-5', 'tx-4']
        !small.find('tx-1').isPresent()
        small.saveIfAbsent(decision('tx-5')).is(small.find('tx-5').get())
    }

    def "the Redis store keeps the first decision for a transaction and lists recent ones"() {
        given:
        def redis = TestRedis.client()
        redis.flushAll()
        def redisStore = new RedisDecisionStore(redis, Json.mapper(), Duration.ofDays(1))
        def original = decision('tx-a')

        when:
        redisStore.saveIfAbsent(original)
        def raced = redisStore.saveIfAbsent(new RiskDecision('tx-a', 'acct', Decision.BLOCK, 99,
                [new Reason('X', 99, '')], 1d, null, false, 'm', 'r', 1, T0))
        redisStore.saveIfAbsent(decision('tx-b'))

        then:
        raced == original
        redisStore.find('tx-a').get() == original
        redisStore.recent(10)*.transactionId() == ['tx-b', 'tx-a']
        !redisStore.find('missing').isPresent()
        redis.ttl('risk:decision:tx-a') > 0

        cleanup:
        redis.close()
    }

    def "an empty Redis store lists nothing"() {
        given:
        def redis = TestRedis.client()
        redis.flushAll()

        expect:
        new RedisDecisionStore(redis, Json.mapper(), Duration.ofDays(1)).recent(5).isEmpty()

        cleanup:
        redis.close()
    }
}
