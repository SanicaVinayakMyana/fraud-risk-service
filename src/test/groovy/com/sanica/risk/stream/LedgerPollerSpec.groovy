package com.sanica.risk.stream

import com.sanica.risk.api.Json
import com.sanica.risk.domain.Channel
import io.javalin.Javalin
import spock.lang.AutoCleanup
import spock.lang.Specification
import spock.util.concurrent.PollingConditions

import java.time.Duration

/** Runs the poller against a stub of the payments-ledger API, with that service's real response shape. */
class LedgerPollerSpec extends Specification {

    def page = []
    int status = 200

    @AutoCleanup('stop')
    Javalin ledger = Javalin.create().get('/transactions') { ctx ->
        assert ctx.queryParam('limit') == '200'
        ctx.status(status).contentType('application/json').result(Json.mapper().writeValueAsString(page))
    }.start(0)

    def received = []

    @AutoCleanup
    LedgerPoller poller = new LedgerPoller("http://localhost:${ledger.port()}/", Json.mapper(), { received << it })

    static Map ledgerTx(String id, String type, String amount, String createdAt) {
        [id                  : id, type: type,
         sourceAccountId     : type == 'DEPOSIT' ? 'external-usd' : 'alice',
         destinationAccountId: type == 'DEPOSIT' ? 'alice' : 'bob',
         sourceAmount        : amount, sourceCurrency: 'USD',
         destinationAmount   : amount, destinationCurrency: 'USD', fxRate: '1',
         createdAt           : createdAt, entries: []]
    }

    def "new ledger transactions are forwarded oldest first, each exactly once"() {
        given: 'the ledger lists newest first'
        page = [ledgerTx('t2', 'TRANSFER', '40.00', '2026-10-25T12:00:05Z'),
                ledgerTx('t1', 'DEPOSIT', '500.00', '2026-10-25T12:00:00Z')]

        when:
        def first = poller.pollOnce()
        page = [ledgerTx('t3', 'TRANSFER', '1.00', '2026-10-25T12:00:09Z')] + page
        def second = poller.pollOnce()

        then:
        first == 2
        second == 1
        received*.transactionId() == ['t1', 't2', 't3']
    }

    def "ledger transactions map onto risk events from the paying account's point of view"() {
        given:
        page = [ledgerTx('t2', 'TRANSFER', '40.00', '2026-10-25T12:00:05Z'),
                ledgerTx('t1', 'DEPOSIT', '500.00', '2026-10-25T12:00:00Z')]

        when:
        poller.pollOnce()
        def (deposit, transfer) = received

        then:
        deposit.accountId() == 'alice'
        deposit.counterpartyId() == 'external-usd'
        deposit.channel() == Channel.DEPOSIT
        transfer.accountId() == 'alice'
        transfer.counterpartyId() == 'bob'
        transfer.channel() == Channel.TRANSFER
        transfer.amount() == 40.00
        transfer.timestamp().toString() == '2026-10-25T12:00:05Z'
        transfer.validate()
    }

    def "an unhealthy ledger is reported and retried on the next tick"() {
        given:
        status = 503

        when:
        poller.pollOnce()

        then:
        def e = thrown(IllegalStateException)
        e.message.contains('503')
    }

    def "the scheduled poller keeps going after failures"() {
        given:
        status = 500
        poller.start(Duration.ofMillis(20))

        when:
        Thread.sleep(60)
        status = 200
        page = [ledgerTx('t1', 'TRANSFER', '5.00', '2026-10-25T12:00:00Z')]

        then:
        new PollingConditions(timeout: 5).eventually {
            assert received*.transactionId() == ['t1']
        }
    }
}
