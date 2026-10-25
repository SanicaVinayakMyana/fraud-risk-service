package com.sanica.risk.rules

import static com.sanica.risk.support.Events.tx

import com.sanica.risk.api.Json
import com.sanica.risk.domain.Channel
import com.sanica.risk.domain.Decision
import com.sanica.risk.features.Features
import com.sanica.risk.features.VelocitySnapshot
import spock.lang.Specification

class RuleParserSpec extends Specification {

    def parser = new RuleParser(Json.mapper())

    static RuleContext context(Map o = [:]) {
        def event = tx(amount: o.amount ?: 50, channel: o.channel ?: Channel.CARD, country: o.containsKey('country') ? o.country : 'US')
        def snapshot = new VelocitySnapshot(o.c60 ?: 1, 1, 50d, o.cps ?: 1, o.newDevice ?: false, false, 10, 50d, 5d, 60d)
        new RuleContext(event, Features.of(event, snapshot), o.ml ?: 0.1d)
    }

    static String rules(String when, Map extra = [:]) {
        def fields = extra.collect { k, v -> "\"$k\": ${v instanceof String ? "\"$v\"" : v}," }.join(' ')
        """{"version": "t1", "rules": [{"id": "R1", "description": "test rule", "points": 40, $fields "when": $when}]}"""
    }

    def "the shipped rule set parses, with enforced and shadow rules"() {
        when:
        def set = parser.parseClasspath('/rules.json')

        then:
        set.version() == '2026-10-25.1'
        set.rules().size() >= 10
        set.rules().any { it.mode() == RuleMode.SHADOW }
        set.find('CARD_TESTING').action() == Decision.BLOCK
        set.find('NEW_DEVICE_HIGH_AMOUNT').when().fields() == ['newDevice', 'amountUsd'] as Set
    }

    def "comparisons cover every operator"() {
        given:
        def set = parser.parse(rules("""{"field": "txCount60s", "op": "$op", "value": 5}"""))

        expect:
        !set.evaluate(context(c60: count)).enforced().isEmpty() == hit

        where:
        op   | count || hit
        '>'  | 6     || true
        '>'  | 5     || false
        '>=' | 5     || true
        '<'  | 4     || true
        '<=' | 6     || false
        '==' | 5     || true
        '!=' | 5     || false
    }

    def "all, any, not and in compose"() {
        given:
        def set = parser.parse(rules('''{"all": [
                {"field": "channel", "in": ["CARD", "TRANSFER"]},
                {"any": [{"field": "newDevice", "op": "==", "value": true}, {"field": "mlScore", "op": ">=", "value": 0.9}]},
                {"not": {"field": "country", "in": ["US"]}}]}'''))

        expect:
        !set.evaluate(context(newDevice: true, country: 'FR')).enforced().isEmpty()
        !set.evaluate(context(ml: 0.95d, country: 'FR')).enforced().isEmpty()
        set.evaluate(context(newDevice: true, country: 'US')).enforced().isEmpty()
        set.evaluate(context(newDevice: true, country: 'FR', channel: Channel.DEPOSIT)).enforced().isEmpty()
        set.evaluate(context(country: 'FR')).enforced().isEmpty()
        !set.evaluate(context(newDevice: true, country: null)).enforced().isEmpty()   // no country is "not US"
    }

    def "a hit carries the rule's reason code, points and the evidence it matched on"() {
        given:
        def set = parser.parse(rules('{"all": [{"field": "txCount60s", "op": ">", "value": 5}, {"field": "amountUsd", "op": "<", "value": 10}]}',
                [reasonCode: 'CARD_TESTING']))

        when:
        def hit = set.evaluate(context(c60: 9, amount: '2.50')).enforced()[0]

        then:
        hit.reason().code() == 'CARD_TESTING'
        hit.reason().points() == 40
        hit.reason().detail() == 'test rule (txCount60s=9, amountUsd=2.50)'
    }

    def "shadow rules are reported separately and disabled rules are skipped"() {
        given:
        def set = parser.parse("""{"version": "t1", "rules": [
            {"id": "A", "description": "a", "points": 10, "mode": "SHADOW", "when": {"field": "amountUsd", "op": ">", "value": 0}},
            {"id": "B", "description": "b", "points": 10, "mode": "DISABLED", "when": {"field": "amountUsd", "op": ">", "value": 0}},
            {"id": "C", "description": "c", "points": 10, "when": {"field": "amountUsd", "op": ">", "value": 0}}]}""")

        when:
        def evaluation = set.evaluate(context())

        then:
        evaluation.shadow()*.rule()*.id() == ['A']
        evaluation.enforced()*.rule()*.id() == ['C']
        evaluation.enforced()[0].reason().code() == 'C'   // reasonCode defaults to the id
    }

    def "mistakes are caught when the rules are loaded: #problem"() {
        when:
        parser.parse(json)

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains(message)

        where:
        problem              | json                                                                                                 || message
        'unknown field'      | rules('{"field": "txCount5s", "op": ">", "value": 5}')                                               || 'Unknown field txCount5s'
        'unknown operator'   | rules('{"field": "txCount60s", "op": "=>", "value": 5}')                                             || "Unknown operator '=>'"
        'missing value'      | rules('{"field": "txCount60s", "op": ">"}')                                                          || 'needs a numeric or boolean'
        'in on numeric'      | rules('{"field": "amountUsd", "in": ["1"]}')                                                         || "'in' needs a text field"
        'empty all'          | rules('{"all": []}')                                                                                 || 'non-empty array'
        'not an object'      | rules('"amountUsd > 5"')                                                                             || 'must be an object'
        'bad points'         | rules('{"field": "amountUsd", "op": ">", "value": 5}').replace('"points": 40', '"points": 400')       || 'points must be between'
        'bad mode'           | rules('{"field": "amountUsd", "op": ">", "value": 5}', [mode: 'SOMETIMES'])                          || 'SOMETIMES'
        'no version'         | '{"rules": []}'                                                                                      || "needs a 'version'"
        'invalid json'       | '{"version": '                                                                                       || 'not valid JSON'
        'duplicate ids'      | '{"version": "x", "rules": [{"id": "A", "description": "a", "when": {"field": "amountUsd", "op": ">", "value": 1}}, {"id": "A", "description": "a", "when": {"field": "amountUsd", "op": ">", "value": 1}}]}' || 'Duplicate rule id A'
    }

    def "rules can be loaded from a file"() {
        given:
        def file = File.createTempFile('rules', '.json')
        file.text = rules('{"field": "amountUsd", "op": ">", "value": 5}')

        expect:
        parser.parseFile(file.toPath()).find('R1').points() == 40

        cleanup:
        file.delete()
    }

    def "a missing classpath resource is reported"() {
        when:
        parser.parseClasspath('/nope.json')

        then:
        thrown(IllegalArgumentException)
    }
}
