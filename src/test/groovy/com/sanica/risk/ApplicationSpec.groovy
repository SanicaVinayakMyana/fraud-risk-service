package com.sanica.risk

import com.sanica.risk.api.Json
import com.sanica.risk.domain.RiskDecision
import com.sanica.risk.stream.RedisStreams
import com.sanica.risk.support.Events
import com.sanica.risk.support.TestRedis
import groovy.json.JsonSlurper
import io.javalin.Javalin
import redis.clients.jedis.StreamEntryID
import spock.lang.Specification
import spock.util.concurrent.PollingConditions

import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant

/** The two services talking: ledger events in over Redis Streams (or polling), decisions out on a stream. */
class ApplicationSpec extends Specification {

    def http = HttpClient.newHttpClient()

    def get(Application app, String path) {
        def response = http.send(HttpRequest.newBuilder(URI.create("http://localhost:${app.port()}$path")).build(),
                HttpResponse.BodyHandlers.ofString())
        new JsonSlurper().parseText(response.body())
    }

    def post(Application app, String path) {
        http.send(HttpRequest.newBuilder(URI.create("http://localhost:${app.port()}$path"))
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString()).statusCode()
    }

    def "with Redis: an event XADDed by the ledger is scored and its decision published on risk.decisions"() {
        given:
        def redis = TestRedis.client()
        redis.flushAll()
        def app = Application.start(Config.fromMap([PORT: '0', REDIS_URL: TestRedis.url(), CONSUMER_NAME: 'it-1']))
        def event = Events.tx(id: 'ledger-tx-1', account: 'alice', amount: '30000', at: Instant.now())

        when: 'the ledger publishes a transaction'
        new RedisStreams(redis, Json.mapper()).publishEvent('ledger.transactions', event)

        then: 'the decision is stored and published for the ledger to act on'
        new PollingConditions(timeout: 10).eventually {
            assert get(app, '/v1/decisions/ledger-tx-1').decision == 'BLOCK'
        }
        def published = redis.xrange('risk.decisions', (StreamEntryID) null, (StreamEntryID) null)
        published.size() == 1
        Json.mapper().readValue(published[0].fields.payload, RiskDecision).primaryReason() == 'HARD_AMOUNT_LIMIT'

        cleanup:
        app?.close()
        redis.close()
    }

    def "with LEDGER_URL: the ledger's transactions are pulled and scored"() {
        given:
        def ledger = Javalin.create().get('/transactions') { ctx ->
            ctx.contentType('application/json').result('''[{"id": "l-1", "type": "TRANSFER",
                "sourceAccountId": "alice", "destinationAccountId": "bob", "sourceAmount": "12.00",
                "sourceCurrency": "EUR", "createdAt": "2026-10-25T12:00:00Z"}]''')
        }.start(0)
        def app = Application.start(Config.fromMap([PORT: '0', LEDGER_URL: "http://localhost:${ledger.port()}".toString(),
                                                    LEDGER_POLL_MS: '50']))

        expect:
        new PollingConditions(timeout: 10).eventually {
            assert get(app, '/v1/decisions/l-1').accountId == 'alice'
        }

        cleanup:
        app?.close()
        ledger.stop()
    }

    def "demo mode generates traffic and attacks on request"() {
        given:
        def app = Application.start(Config.fromMap([PORT: '0', DEMO_MODE: 'true']))

        when:
        def status = post(app, '/v1/demo/attacks/card-testing')

        then:
        status == 202
        post(app, '/v1/demo/attacks/sideways') == 400
        post(app, '/v1/demo/attacks/normal') == 400
        new PollingConditions(timeout: 15).eventually {
            assert get(app, '/v1/decisions?limit=500').any { it.decision == 'BLOCK' }
        }

        cleanup:
        app?.close()
    }
}
