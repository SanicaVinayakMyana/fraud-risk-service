package com.sanica.risk.engine

import com.sanica.risk.domain.Decision
import com.sanica.risk.domain.Reason
import com.sanica.risk.rules.Condition
import com.sanica.risk.rules.Rule
import com.sanica.risk.rules.RuleMode
import com.sanica.risk.rules.RuleSet.Hit
import spock.lang.Specification

class DecisionMakerSpec extends Specification {

    def maker = new DecisionMaker(ScoringPolicy.DEFAULT)   // review >= 50, block >= 80, ML up to 40 points

    static Hit hit(String code, int points, Decision action = null) {
        def rule = new Rule(code, code, code, points, action, RuleMode.ENFORCE,
                new Condition.Compare('amountUsd', Condition.Op.GT, 0))
        new Hit(rule, new Reason(code, points, "$code detail"))
    }

    def "score thresholds: #points rule points with ML #ml gives #decision"() {
        when:
        def outcome = maker.decide(ml, points ? [hit('R', points)] : [])

        then:
        outcome.decision() == decision
        outcome.score() == score

        where:
        points | ml   || decision        | score
        0      | 0.1  || Decision.ALLOW  | 0
        45     | 0.1  || Decision.ALLOW  | 45
        50     | 0.1  || Decision.REVIEW | 50
        45     | 0.5  || Decision.REVIEW | 65        // 45 + 40 * 0.5
        45     | 0.9  || Decision.BLOCK  | 81        // 45 + 40 * 0.9
    }

    def "the model adds up to mlWeight points, and nothing below the reason threshold"() {
        expect:
        maker.decide(0.49d, []).score() == 0
        maker.decide(0.5d, []).score() == 20
        maker.decide(1.0d, []).score() == 40
        maker.decide(1.0d, []).decision() == Decision.ALLOW
        maker.decide(1.0d, [hit('R', 10)]).decision() == Decision.REVIEW
    }

    def "scores are capped at 100"() {
        expect:
        maker.decide(1.0d, [hit('A', 100), hit('B', 100)]).score() == 100
    }

    def "a rule with an action forces at least that decision whatever the score"() {
        when:
        def outcome = maker.decide(0.0d, [hit('SMALL', 5), hit('LIMIT', 1, Decision.BLOCK)])

        then:
        outcome.decision() == Decision.BLOCK
        outcome.score() == 6
        outcome.reasons()*.code() == ['LIMIT', 'SMALL']   // the forcing rule explains the decision first
    }

    def "reasons are ordered by contribution and every point is accounted for"() {
        when:
        def outcome = maker.decide(0.9d, [hit('A', 10), hit('B', 30)])

        then:
        outcome.reasons()*.code() == ['ML_ANOMALY', 'B', 'A']
        outcome.reasons()*.points().sum() == outcome.score()
        outcome.reasons()[0].detail() == 'Isolation Forest anomaly score 0.90'
    }

    def "an allow with nothing to report still has a reason code"() {
        expect:
        maker.decide(0.0d, []).reasons()*.code() == ['LOW_RISK']
    }

    def "invalid policies are rejected"() {
        when:
        new ScoringPolicy(review, block, weight, 0.5)

        then:
        thrown(IllegalArgumentException)

        where:
        review | block | weight
        0      | 80    | 40
        80     | 50    | 40
        50     | 101   | 40
        50     | 80    | 101
    }
}
