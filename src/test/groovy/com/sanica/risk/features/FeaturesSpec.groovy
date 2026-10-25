package com.sanica.risk.features

import static com.sanica.risk.support.Events.tx

import spock.lang.Specification

import java.time.Instant

class FeaturesSpec extends Specification {

    static VelocitySnapshot snapshot(Map o = [:]) {
        new VelocitySnapshot(o.c60 ?: 1, o.c1h ?: 1, o.sum ?: 25d, o.cps ?: 1, o.newDevice ?: false,
                o.newCountry ?: false, o.prior ?: 0L, o.mean ?: 0d, o.std ?: 0d,
                o.containsKey('since') ? o.since : -1d)
    }

    def "z-score needs at least five prior transactions"() {
        expect:
        Features.of(tx(amount: 500), snapshot(prior: prior, mean: 50d, std: 10d)).amountZScore() == z

        where:
        prior || z
        4     || 0d
        5     || 20d     // (500 - 50) / 10 = 45, clamped to 20
    }

    def "a perfectly regular account does not turn small changes into huge z-scores"() {
        expect: 'the spread is floored at 10% of the mean'
        Features.of(tx(amount: 110), snapshot(prior: 50, mean: 100d, std: 0d)).amountZScore() == 1d
    }

    def "model input has one value per declared feature, in order"() {
        given:
        def f = Features.of(tx(amount: 99, at: Instant.parse('2026-10-25T06:00:00Z')),
                snapshot(c60: 3, c1h: 7, sum: 300d, cps: 4, newDevice: true, prior: 10, mean: 30d, std: 5d, since: 120d))

        when:
        def input = f.modelInput()

        then:
        input.length == Features.MODEL_INPUTS.size()
        input[0] == (float) Math.log1p(99)
        input[1] == 3f
        input[2] == 7f
        input[5] == 1f
        input[6] == 0f
        input[8] == (float) Math.log1p(120)
        Math.abs(input[9] - 1f) < 1e-6      // 06:00 is a quarter of the way round the clock
        Math.abs(input[10]) < 1e-6
    }

    def "a first transaction is encoded as 30 days since the last one"() {
        expect:
        Features.of(tx(), snapshot(since: -1d)).modelInput()[8] == (float) Math.log1p(30 * 86_400)
    }

    def "rules see booleans as 1 and 0"() {
        expect:
        Features.of(tx(), snapshot(newDevice: true)).asMap().newDevice == 1d
        Features.of(tx(), snapshot()).asMap().newCountry == 0d
    }
}
