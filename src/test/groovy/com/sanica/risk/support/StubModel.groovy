package com.sanica.risk.support

import com.sanica.risk.features.Features
import com.sanica.risk.ml.AnomalyModel

/** A model whose score the test controls. */
class StubModel implements AnomalyModel {

    Closure<Double> scorer = { Features f -> 0.1d }

    @Override
    double score(Features features) {
        scorer(features)
    }

    @Override
    String version() {
        'stub-model'
    }
}
