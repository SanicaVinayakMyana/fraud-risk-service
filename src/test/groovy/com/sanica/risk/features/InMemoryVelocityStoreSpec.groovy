package com.sanica.risk.features

import static com.sanica.risk.support.Events.T0
import static com.sanica.risk.support.Events.tx

import spock.lang.Specification

import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

class InMemoryVelocityStoreSpec extends Specification {

    def store = new InMemoryVelocityStore()

    def "the first transaction of an account has no history"() {
        when:
        def s = store.record(tx(amount: '40.00'))

        then:
        s.txCount60s() == 1
        s.txCount1h() == 1
        s.amountSum1hUsd() == 40d
        s.distinctCounterparties1h() == 1
        !s.newDevice()
        !s.newCountry()
        s.priorTxCount() == 0
        s.secondsSinceLastTx() == -1d
    }

    def "the 60 second window is sliding and excludes events exactly 60 seconds old"() {
        given:
        store.record(tx(at: T0))
        store.record(tx(at: T0.plusSeconds(30)))

        expect:
        store.record(tx(at: T0.plusSeconds(59))).txCount60s() == 3
        store.record(tx(at: T0.plusSeconds(60))).txCount60s() == 3   // T0 has dropped out, +60 is in
        store.record(tx(at: T0.plusSeconds(200))).txCount60s() == 1
        store.record(tx(at: T0.plusSeconds(200))).txCount1h() == 6
    }

    def "the hour window drops old transactions from counts and sums"() {
        given:
        store.record(tx(amount: '100', at: T0))
        store.record(tx(amount: '10', at: T0.plusSeconds(1_800)))

        when:
        def s = store.record(tx(amount: '1', at: T0.plusSeconds(3_600)))

        then:
        s.txCount1h() == 2
        s.amountSum1hUsd() == 11d
    }

    def "amounts are normalised to USD"() {
        expect:
        store.record(tx(amount: '100', currency: 'GBP')).amountSum1hUsd() == 127d
    }

    def "new device and new country are only flagged once the account has history"() {
        given:
        store.record(tx(device: 'phone', country: 'US', at: T0))

        expect:
        with(store.record(tx(device: 'laptop', country: 'FR', at: T0.plusSeconds(10)))) {
            newDevice()
            newCountry()
        }
        with(store.record(tx(device: 'laptop', country: 'FR', at: T0.plusSeconds(20)))) {
            !newDevice()
            !newCountry()
        }
        !store.record(tx(device: null, country: null, at: T0.plusSeconds(30))).newDevice()
    }

    def "distinct counterparties are counted once each within the hour"() {
        given:
        ['a', 'b', 'a', 'c'].eachWithIndex { cp, i -> store.record(tx(counterparty: cp, at: T0.plusSeconds(i))) }

        expect:
        store.record(tx(counterparty: 'b', at: T0.plusSeconds(10))).distinctCounterparties1h() == 3
        store.record(tx(counterparty: 'd', at: T0.plusSeconds(3_605))).distinctCounterparties1h() == 2  // b at +10 and d
    }

    def "prior statistics describe the history before the current transaction"() {
        given:
        [10, 20, 30].eachWithIndex { amount, i -> store.record(tx(amount: amount, at: T0.plusSeconds(i * 100))) }

        when:
        def s = store.record(tx(amount: 1000, at: T0.plusSeconds(500)))

        then:
        s.priorTxCount() == 3
        s.priorMeanUsd() == 20d
        s.priorStdUsd() == 10d
        s.secondsSinceLastTx() == 300d
    }

    def "recording the same transaction twice does not count it twice"() {
        given:
        def event = tx(id: 'tx-1')
        store.record(event)

        when:
        def again = store.record(event)

        then:
        again.txCount60s() == 1
        again.priorTxCount() == 1
    }

    def "accounts are independent"() {
        given:
        5.times { store.record(tx(account: 'a', at: T0.plusSeconds(it))) }

        expect:
        store.record(tx(account: 'b', at: T0.plusSeconds(6))).txCount60s() == 1
    }

    def "concurrent writers on one account never lose an update"() {
        given:
        def pool = Executors.newFixedThreadPool(16)
        def start = new CountDownLatch(1)

        when:
        def futures = (1..400).collect { i ->
            pool.submit({ start.await(); store.record(tx(at: T0.plusMillis(i))) } as Callable)
        }
        start.countDown()
        def counts = futures.collect { it.get().txCount1h() }
        pool.shutdown()

        then: 'every count from 1 to 400 was observed exactly once'
        counts.sort() == (1..400).toList()
    }
}
