package com.sanica.risk

import com.sanica.risk.domain.Decision
import spock.lang.Specification

import java.time.Duration

class ConfigSpec extends Specification {

    def "defaults run the service in memory on port 8081 with a 50 ms budget"() {
        when:
        def c = Config.fromMap([:])

        then:
        c.port() == 8081
        c.redisUrl() == null
        c.inputStream() == 'ledger.transactions'
        c.decisionStream() == 'risk.decisions'
        c.latencyBudget() == Duration.ofMillis(50)
        c.fallbackDecision() == Decision.REVIEW
        c.policy().reviewThreshold() == 50
        c.policy().blockThreshold() == 80
        !c.demoMode()
        c.adminToken() == null
    }

    def "everything can be overridden from the environment"() {
        when:
        def c = Config.fromMap([PORT: '9000', REDIS_URL: 'redis://r:6379', LATENCY_BUDGET_MS: '20',
                                FALLBACK_DECISION: 'ALLOW', REVIEW_THRESHOLD: '40', BLOCK_THRESHOLD: '70',
                                ML_WEIGHT: '30', LEDGER_URL: 'http://ledger:8080', ADMIN_TOKEN: 't',
                                DEMO_MODE: 'true', HOSTNAME: 'pod-7', RULES_PATH: ' '])

        then:
        c.port() == 9000
        c.redisUrl() == 'redis://r:6379'
        c.latencyBudget() == Duration.ofMillis(20)
        c.fallbackDecision() == Decision.ALLOW
        c.policy().mlWeight() == 30
        c.ledgerUrl() == 'http://ledger:8080'
        c.consumerName() == 'pod-7'
        c.rulesPath() == null
        c.demoMode()
    }
}
