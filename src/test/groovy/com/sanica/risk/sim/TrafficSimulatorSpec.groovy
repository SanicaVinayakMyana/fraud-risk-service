package com.sanica.risk.sim

import static com.sanica.risk.support.Events.T0

import com.sanica.risk.features.Features
import com.sanica.risk.sim.TrafficSimulator.Scenario
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Path
import java.time.Duration

class TrafficSimulatorSpec extends Specification {

    @TempDir
    Path dir

    def "traffic is time-ordered, valid, labelled and contains every attack type"() {
        when:
        def events = new TrafficSimulator(1).generate(200, T0, Duration.ofDays(5), 0.5)

        then:
        events.size() > 1_000
        events*.event()*.timestamp() == events*.event()*.timestamp().sort(false)
        events.every { it.event().validate() }
        events*.scenario().toSet() == (Scenario.values() as Set)
        events.count { it.fraud() } < events.size() * 0.2
    }

    def "card testing is a burst of tiny card payments from one new device"() {
        given:
        def sim = new TrafficSimulator(3)
        def profile = sim.newProfile('victim')

        when:
        def burst = sim.attack(Scenario.CARD_TESTING, profile, T0)

        then:
        burst.size() >= 8
        burst.every { it.amountUsd() <= 5.01 }
        burst*.deviceId().toSet().size() == 1
        burst.last().timestamp().isBefore(T0.plusSeconds(120))
    }

    def "every scenario produces events for the attacked account"() {
        given:
        def sim = new TrafficSimulator(5)
        def profile = sim.newProfile('victim')

        expect:
        Scenario.values().every { s -> sim.attack(s, profile, T0).every { it.accountId() == 'victim' } }
    }

    def "the same seed gives the same traffic"() {
        expect:
        new TrafficSimulator(9).generate(20, T0, Duration.ofDays(2), 0.5)*.event()*.amount() ==
                new TrafficSimulator(9).generate(20, T0, Duration.ofDays(2), 0.5)*.event()*.amount()
    }

    def "the training export has the model's columns plus labels"() {
        given:
        def out = dir.resolve('data/features.csv')

        when:
        def rows = TrainingDataExporter.export(out, 30, 11L)
        def lines = out.toFile().readLines()

        then:
        lines[0] == (Features.MODEL_INPUTS + ['is_fraud', 'scenario']).join(',')
        lines.size() == rows + 1
        lines[1].split(',').length == Features.MODEL_INPUTS.size() + 2
    }

    def "the exporter's main writes to the given path"() {
        given:
        def out = dir.resolve('main.csv')

        when:
        TrainingDataExporter.main(out.toString(), '5')

        then:
        out.toFile().readLines().size() > 1
    }
}
