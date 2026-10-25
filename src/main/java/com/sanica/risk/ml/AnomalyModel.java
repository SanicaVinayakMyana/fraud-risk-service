package com.sanica.risk.ml;

import com.sanica.risk.features.Features;

/** An unsupervised anomaly detector. Scores are in [0, 1]; higher means more unusual for this population. */
public interface AnomalyModel extends AutoCloseable {

    double score(Features features);

    String version();

    @Override
    default void close() {
    }
}
