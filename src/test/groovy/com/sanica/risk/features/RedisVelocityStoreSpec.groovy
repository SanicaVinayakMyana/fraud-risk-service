package com.sanica.risk.features

import static com.sanica.risk.support.Events.T0
import static com.sanica.risk.support.Events.tx

import com.sanica.risk.domain.Channel
import com.sanica.risk.support.TestRedis
import redis.clients.jedis.JedisPooled
import spock.lang.Shared
import spock.lang.Specification

import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

class RedisVelocityStoreSpec extends Specification {

    @Shared
    JedisPooled redis = TestRedis.client()

    RedisVelocityStore store

    def setup() {
        redis.flushAll()
        store = new RedisVelocityStore(redis, Duration.ofDays(90))
    }

    def "the Lua script and the in-memory store agree on a long randomised history"() {
        given: 'out-of-order timestamps, duplicates, missing devices and four accounts sharing counterparties'
        def random = new Random(7)
        def memory = new InMemoryVelocityStore()
        def events = []
        def at = T0
        600.times {
            at = at.plusMillis(random.nextInt(40_000) - 5_000)
            events << tx(
                    id: random.nextInt(10) == 0 && events ? events[random.nextInt(events.size())].transactionId() : UUID.randomUUID().toString(),
                    account: "acct-${random.nextInt(4)}",
                    counterparty: random.nextInt(5) == 0 ? null : "cp-${random.nextInt(12)}",
                    amount: String.format('%.2f', 1 + random.nextDouble() * 500),
                    currency: ['USD', 'EUR', 'GBP', 'INR'][random.nextInt(4)],
                    channel: Channel.values()[random.nextInt(4)],
                    device: random.nextInt(6) == 0 ? null : "dev-${random.nextInt(5)}",
                    country: random.nextInt(6) == 0 ? null : ['US', 'DE', 'FR'][random.nextInt(3)],
                    at: at)
        }

        expect:
        events.each { e ->
            def fromRedis = store.record(e)
            def fromMemory = memory.record(e)
            assert fromRedis.txCount60s() == fromMemory.txCount60s()
            assert fromRedis.txCount1h() == fromMemory.txCount1h()
            assert fromRedis.distinctCounterparties1h() == fromMemory.distinctCounterparties1h()
            assert fromRedis.newDevice() == fromMemory.newDevice()
            assert fromRedis.newCountry() == fromMemory.newCountry()
            assert fromRedis.priorTxCount() == fromMemory.priorTxCount()
            assert Math.abs(fromRedis.amountSum1hUsd() - fromMemory.amountSum1hUsd()) < 1e-6
            assert Math.abs(fromRedis.priorMeanUsd() - fromMemory.priorMeanUsd()) < 1e-6
            assert Math.abs(fromRedis.priorStdUsd() - fromMemory.priorStdUsd()) < 1e-6
            assert Math.abs(fromRedis.secondsSinceLastTx() - fromMemory.secondsSinceLastTx()) < 1e-6
        }
    }

    def "card testing burst: the sixth payment within a minute sees six"() {
        when:
        def snapshots = (0..5).collect { store.record(tx(amount: '1.00', at: T0.plusSeconds(it * 5))) }

        then:
        snapshots*.txCount60s() == [1, 2, 3, 4, 5, 6]
    }

    def "keys share one hash tag per account and expire"() {
        when:
        store.record(tx(account: 'acct-42'))

        then:
        def keys = redis.keys('risk:*')
        keys.every { it.startsWith('risk:{acct-42}:') }
        keys.every { redis.ttl(it) > 0 }
    }

    def "the script is reloaded after Redis loses its script cache"() {
        given:
        store.record(tx(at: T0))
        redis.scriptFlush()

        expect:
        store.record(tx(at: T0.plusSeconds(1))).txCount60s() == 2
    }

    def "replicas racing on one account get distinct, gap-free counts"() {
        given: 'two stores on separate connections stand in for two service replicas'
        def other = new RedisVelocityStore(TestRedis.client(), Duration.ofDays(90))
        def pool = Executors.newFixedThreadPool(16)
        def start = new CountDownLatch(1)

        when:
        def futures = (1..200).collect { i ->
            pool.submit({ start.await(); (i % 2 ? store : other).record(tx(at: T0.plusMillis(i))) } as Callable)
        }
        start.countDown()
        def counts = futures.collect { it.get().txCount60s() }
        pool.shutdown()

        then:
        counts.sort() == (1..200).toList()
    }
}
