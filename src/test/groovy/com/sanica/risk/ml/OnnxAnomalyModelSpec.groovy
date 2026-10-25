package com.sanica.risk.ml

import static com.sanica.risk.support.Events.T0

import com.sanica.risk.api.Json
import com.sanica.risk.features.Features
import com.sanica.risk.features.InMemoryVelocityStore
import com.sanica.risk.sim.TrafficSimulator
import com.sanica.risk.sim.TrafficSimulator.Scenario
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

import java.time.Duration

class OnnxAnomalyModelSpec extends Specification {

    @Shared
    @AutoCleanup
    OnnxAnomalyModel model = OnnxAnomalyModel.loadDefault(Json.mapper())

    def "the shipped model matches the service's feature order and carries its evaluation"() {
        expect:
        model.metadata().features() == Features.MODEL_INPUTS
        model.version().startsWith('iforest-')
        (model.metadata().metrics().rocAuc as double) > 0.9
    }

    def "attacks score far higher than everyday payments on traffic the model never saw"() {
        given: 'a fresh simulation with a different seed, replayed through the production feature code'
        def events = new TrafficSimulator(2024).generate(300, T0, Duration.ofDays(7), 0.3)
        def store = new InMemoryVelocityStore()
        def scores = [:].withDefault { [] }

        when:
        events.each { e ->
            scores[e.scenario()] << model.score(Features.of(e.event(), store.record(e.event())))
        }
        def normal = scores[Scenario.NORMAL]
        def fraud = Scenario.values().findAll { it != Scenario.NORMAL }.collectMany { scores[it] }

        then:
        median(fraud) > 0.9
        median(normal) < 0.2
        normal.count { it >= 0.5 } / normal.size() < 0.02
        fraud.count { it >= 0.5 } / fraud.size() > 0.85
        (normal + fraud).every { it >= 0 && it <= 1 }
    }

    def "calibration maps the contamination threshold to 0.5 and is monotonic"() {
        expect:
        model.calibrate(0d) == 0.5d
        model.calibrate(-0.1d) > model.calibrate(0d)
        model.calibrate(0.1d) < model.calibrate(0d)
    }

    def "a model trained on different features is refused at startup"() {
        when:
        OnnxAnomalyModel.load(OnnxAnomalyModel.DEFAULT_MODEL, '/model-wrong-features.json', Json.mapper())

        then:
        def e = thrown(IllegalStateException)
        e.message.contains('expects features')
    }

    def "missing model files are reported clearly"() {
        when:
        OnnxAnomalyModel.load('/model/missing.onnx', OnnxAnomalyModel.DEFAULT_METADATA, Json.mapper())

        then:
        def e = thrown(IllegalStateException)
        e.message.contains('not found')
    }

    private static double median(List<Double> values) {
        def sorted = values.sort(false)
        sorted[sorted.size().intdiv(2)]
    }
}
