package com.sanica.risk.rules

import com.sanica.risk.api.Json
import com.sanica.risk.domain.ValidationException
import spock.lang.Specification

class RuleRepositorySpec extends Specification {

    def repository = new RuleRepository(new RuleParser(Json.mapper()).parseClasspath('/rules.json'))

    def "promoting a shadow rule swaps in a new rule set with a new version"() {
        given:
        def before = repository.current()

        when:
        def rule = repository.setMode('RAPID_REPEAT', RuleMode.ENFORCE)

        then:
        rule.mode() == RuleMode.ENFORCE
        repository.current().version() == '2026-10-25.1-r1'
        before.find('RAPID_REPEAT').mode() == RuleMode.SHADOW   // earlier snapshots are untouched
    }

    def "versions keep counting"() {
        expect:
        RuleRepository.nextVersion('v1') == 'v1-r1'
        RuleRepository.nextVersion('v1-r1') == 'v1-r2'
        RuleRepository.nextVersion('v1-rc') == 'v1-rc-r1'
    }

    def "unknown rules are rejected"() {
        when:
        repository.setMode('NOPE', RuleMode.ENFORCE)

        then:
        thrown(ValidationException)
    }
}
