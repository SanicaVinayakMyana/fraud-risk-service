package com.sanica.risk.api

import com.sanica.risk.Application
import com.sanica.risk.Config
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant

/** The HTTP contract, against the real application (in-memory mode, real ONNX model, shipped rules). */
class RiskApiSpec extends Specification {

    @Shared
    @AutoCleanup
    Application app = Application.start(Config.fromMap([PORT: '0', ADMIN_TOKEN: 'secret']))

    @Shared
    HttpClient http = HttpClient.newHttpClient()

    Map call(String method, String path, Object body = null, Map headers = [:]) {
        def builder = HttpRequest.newBuilder(URI.create("http://localhost:${app.port()}$path"))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body instanceof String ? body : JsonOutput.toJson(body)))
                .header('Content-Type', 'application/json')
        headers.each { k, v -> builder.header(k, v) }
        def response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        def json = response.headers().firstValue('Content-Type').orElse('').contains('json')
                ? new JsonSlurper().parseText(response.body()) : response.body()
        [status: response.statusCode(), body: json]
    }

    static Map event(Map overrides = [:]) {
        [transactionId: UUID.randomUUID().toString(), accountId: 'api-acct', counterpartyId: 'shop-1',
         amount       : 42.50, currency: 'EUR', channel: 'CARD', deviceId: 'dev-1', country: 'DE',
         timestamp    : Instant.now().toString()] + overrides
    }

    def "health reports the model and rule versions"() {
        when:
        def r = call('GET', '/health')

        then:
        r.status == 200
        r.body.status == 'UP'
        r.body.model.startsWith('iforest-')
        r.body.rules == '2026-10-25.1'
    }

    def "scoring returns a decision with reason codes, the model score and the versions used"() {
        when:
        def r = call('POST', '/v1/score', event())

        then:
        r.status == 200
        r.body.decision in ['ALLOW', 'REVIEW', 'BLOCK']
        r.body.reasons.size() >= 1
        r.body.reasons[0].code
        r.body.mlScore >= 0 && r.body.mlScore <= 1
        r.body.modelVersion.startsWith('iforest-')
        r.body.latencyMicros >= 0
    }

    def "unknown fields are tolerated so the ledger can evolve its events"() {
        expect:
        call('POST', '/v1/score', event(merchantCategory: '5411')).status == 200
    }

    def "a hard limit breach is blocked with its reason first"() {
        when:
        def r = call('POST', '/v1/score', event(amount: 50_000, currency: 'USD'))

        then:
        r.body.decision == 'BLOCK'
        r.body.reasons[0].code == 'HARD_AMOUNT_LIMIT'
        r.body.reasons[0].detail.contains('amountUsd=50000')
    }

    def "scoring is idempotent per transaction id"() {
        given:
        def e = event()

        when:
        def first = call('POST', '/v1/score', e)
        def second = call('POST', '/v1/score', e + [amount: 99_999])

        then:
        second.body == first.body
    }

    def "invalid requests get 400 with a reason: #problem"() {
        when:
        def r = call('POST', '/v1/score', body)

        then:
        r.status == 400
        r.body.error == 'VALIDATION_FAILED'
        r.body.message.contains(message)

        where:
        problem          | body                          || message
        'malformed JSON' | '{"amount": '                 || 'not a valid transaction event'
        'empty body'     | 'null'                        || 'Body is required'
        'bad channel'    | event(channel: 'CHEQUE')      || 'not a valid transaction event'
        'negative amount'| event(amount: -1)             || 'amount must be positive'
    }

    def "decisions can be looked up by transaction id and listed newest first"() {
        given:
        def e = event()
        call('POST', '/v1/score', e)

        expect:
        call('GET', "/v1/decisions/${e.transactionId}").body.transactionId == e.transactionId
        call('GET', '/v1/decisions/nope').status == 404
        call('GET', '/v1/decisions?limit=1').body*.transactionId == [e.transactionId]
        call('GET', '/v1/decisions?limit=0').status == 400
        call('GET', '/v1/decisions?limit=x').status == 400
    }

    def "rules are listed with their mode and the fields they read"() {
        when:
        def r = call('GET', '/v1/rules')

        then:
        r.body.version.startsWith('2026-10-25.1')
        with(r.body.rules.find { it.id == 'CARD_TESTING' }) {
            mode == 'ENFORCE'
            action == 'BLOCK'
            fields == ['channel', 'txCount60s', 'amountUsd']
        }
        r.body.rules.findAll { it.mode == 'SHADOW' }*.id.containsAll(['FIRST_TX_HIGH_AMOUNT', 'RAPID_REPEAT'])
    }

    def "changing a rule's mode needs the admin token"() {
        expect:
        call('PUT', '/v1/rules/NIGHT_NEW_DEVICE/mode', [mode: 'ENFORCE']).status == 401
        call('PUT', '/v1/rules/NIGHT_NEW_DEVICE/mode', [mode: 'ENFORCE'], ['X-Admin-Token': 'wrong']).status == 401
    }

    def "a shadow rule can be promoted and rolled back at runtime"() {
        when:
        def promoted = call('PUT', '/v1/rules/NIGHT_NEW_DEVICE/mode', [mode: 'ENFORCE'], ['X-Admin-Token': 'secret'])
        def rolledBack = call('PUT', '/v1/rules/NIGHT_NEW_DEVICE/mode', [mode: 'SHADOW'], ['X-Admin-Token': 'secret'])

        then:
        promoted.status == 200
        promoted.body.mode == 'ENFORCE'
        rolledBack.body.mode == 'SHADOW'
        call('PUT', '/v1/rules/NOPE/mode', [mode: 'ENFORCE'], ['X-Admin-Token': 'secret']).status == 400
        call('PUT', '/v1/rules/NIGHT_NEW_DEVICE/mode', [mode: 'LOUD'], ['X-Admin-Token': 'secret']).status == 400
        call('PUT', '/v1/rules/NIGHT_NEW_DEVICE/mode', [:], ['X-Admin-Token': 'secret']).status == 400
    }

    def "the shadow report counts shadow rule hits"() {
        given: 'a large first payment on a new account matches the FIRST_TX_HIGH_AMOUNT shadow rule'
        call('POST', '/v1/score', event(accountId: 'brand-new', amount: 2500, currency: 'USD'))

        when:
        def report = call('GET', '/v1/shadow/report').body

        then:
        report.evaluated >= 1
        report.rules.find { it.ruleId == 'FIRST_TX_HIGH_AMOUNT' }.hits >= 1
    }

    def "the model endpoint exposes its metadata and evaluation"() {
        when:
        def model = call('GET', '/v1/model').body

        then:
        model.features.size() == 11
        model.metrics.rocAuc > 0.9
    }

    def "Prometheus metrics include the latency histogram"() {
        given:
        call('POST', '/v1/score', event())

        when:
        def r = call('GET', '/metrics')

        then:
        r.status == 200
        r.body.contains('risk_scoring_latency_seconds_bucket')
        r.body.contains('risk_decisions_total')
    }

    def "demo attacks are unavailable when demo mode is off"() {
        expect:
        call('GET', '/v1/demo').body.enabled == false
        call('POST', '/v1/demo/attacks/card-testing', null, ['X-Admin-Token': 'secret']).status == 404
    }

    def "the dashboard is served"() {
        expect:
        call('GET', '/').body.contains('Risk Scoring')
    }
}
