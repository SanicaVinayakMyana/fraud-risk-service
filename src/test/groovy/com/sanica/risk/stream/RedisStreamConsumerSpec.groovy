package com.sanica.risk.stream

import static com.sanica.risk.support.Events.T0
import static com.sanica.risk.support.Events.tx

import com.sanica.risk.api.Json
import com.sanica.risk.domain.Decision
import com.sanica.risk.domain.RiskDecision
import com.sanica.risk.engine.DecisionService
import com.sanica.risk.engine.RedisDecisionStore
import com.sanica.risk.engine.RiskEngine
import com.sanica.risk.engine.ScoringPolicy
import com.sanica.risk.features.RedisVelocityStore
import com.sanica.risk.rules.RuleParser
import com.sanica.risk.rules.RuleRepository
import com.sanica.risk.support.StubModel
import com.sanica.risk.support.TestRedis
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import redis.clients.jedis.JedisPooled
import redis.clients.jedis.StreamEntryID
import redis.clients.jedis.params.XReadGroupParams
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification
import spock.util.concurrent.PollingConditions

import java.time.Clock
import java.time.Duration

class RedisStreamConsumerSpec extends Specification {

    static final String IN = 'ledger.transactions'
    static final String OUT = 'risk.decisions'

    @Shared
    JedisPooled redis = TestRedis.client()
    def mapper = Json.mapper()
    def streams = new RedisStreams(redis, mapper)

    @AutoCleanup
    RiskEngine engine
    DecisionService decisions
    RedisStreamConsumer consumer

    def setup() {
        redis.flushAll()
        engine = new RiskEngine(new RedisVelocityStore(redis, Duration.ofDays(1)), new StubModel(),
                new RuleRepository(new RuleParser(mapper).parseClasspath('/rules.json')), ScoringPolicy.DEFAULT,
                new SimpleMeterRegistry(), Duration.ofMillis(500), Decision.REVIEW, Clock.systemUTC())
        decisions = new DecisionService(engine, new RedisDecisionStore(redis, mapper, Duration.ofDays(1)),
                { RiskDecision d -> streams.publishDecision(OUT, d) })
        consumer = consumer('scorer-1', Duration.ofSeconds(30))
    }

    RedisStreamConsumer consumer(String name, Duration claimIdle) {
        new RedisStreamConsumer(redis, mapper, decisions, IN, 'risk-scorer', name, claimIdle)
    }

    List<RiskDecision> publishedDecisions() {
        redis.xrange(OUT, (StreamEntryID) null, (StreamEntryID) null).collect {
            mapper.readValue(it.fields.payload, RiskDecision)
        }
    }

    def "events on the input stream are scored, published and acknowledged"() {
        given:
        def burst = (0..<9).collect { tx(account: 'card-1', amount: '2.00', at: T0.plusSeconds(it * 2)) }
        burst.each { streams.publishEvent(IN, it) }
        consumer.start()

        expect:
        new PollingConditions(timeout: 10).eventually {
            assert publishedDecisions().size() == 9
        }
        publishedDecisions().last().decision() == Decision.BLOCK
        publishedDecisions().last().primaryReason() == 'CARD_TESTING'
        redis.xpending(IN, 'risk-scorer').total == 0
        consumer.processed() == 9

        cleanup:
        consumer.close()
    }

    def "a redelivered event is not scored twice"() {
        given:
        def event = tx(id: 'dup')
        consumer.start()
        streams.publishEvent(IN, event)
        streams.publishEvent(IN, event)

        expect:
        new PollingConditions(timeout: 10).eventually {
            assert consumer.processed() == 2
        }
        publishedDecisions().size() == 1

        cleanup:
        consumer.close()
    }

    def "malformed events go to the dead-letter stream and do not block the group"() {
        given:
        redis.xadd(IN, StreamEntryID.NEW_ENTRY, [payload: '{not json'])
        redis.xadd(IN, StreamEntryID.NEW_ENTRY, [payload: mapper.writeValueAsString(tx(amount: '-5'))])
        streams.publishEvent(IN, tx(id: 'good'))
        consumer.start()

        expect:
        new PollingConditions(timeout: 10).eventually {
            assert consumer.processed() == 1
            assert consumer.deadLettered() == 2
        }
        redis.xlen("${IN}.dlq") == 2
        publishedDecisions()*.transactionId() == ['good']
        redis.xpending(IN, 'risk-scorer').total == 0

        cleanup:
        consumer.close()
    }

    def "entries abandoned by a crashed replica are reclaimed by another"() {
        given: 'replica-1 reads an entry and dies before acknowledging it'
        consumer('replica-1', Duration.ofMillis(1)).with { it.start(); it.close() }
        streams.publishEvent(IN, tx(id: 'orphan'))
        redis.xreadGroup('risk-scorer', 'replica-1', XReadGroupParams.xReadGroupParams().count(10),
                [(IN): StreamEntryID.XREADGROUP_UNDELIVERED_ENTRY])
        assert redis.xpending(IN, 'risk-scorer').total == 1
        Thread.sleep(20)

        when: 'replica-2 runs its reclaim pass'
        def survivor = consumer('replica-2', Duration.ofMillis(10))
        def reclaimed = survivor.reclaimAbandoned()

        then:
        reclaimed == 1
        publishedDecisions()*.transactionId() == ['orphan']
        redis.xpending(IN, 'risk-scorer').total == 0
    }

    def "starting twice reuses the existing consumer group"() {
        when:
        consumer.start().close()
        consumer('scorer-2', Duration.ofSeconds(30)).start().close()

        then:
        noExceptionThrown()
        redis.xinfoGroups(IN).size() == 1
    }

    def "polling an empty stream returns nothing"() {
        given:
        consumer.start().close()

        expect:
        consumer.pollOnce(Duration.ofMillis(10)) == 0
    }
}
