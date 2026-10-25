package com.sanica.risk.ml;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;

/**
 * Written by {@code ml/train_isolation_forest.py} next to the ONNX file.
 *
 * <p>The ONNX graph outputs the Isolation Forest's raw {@code decision_function}: negative for outliers, positive
 * for inliers, with 0 at the training contamination threshold. {@code center} and {@code scale} turn it into a
 * probability-like score with a logistic curve: {@code 1 / (1 + exp((raw - center) / scale))}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ModelMetadata(
        String version,
        String inputName,
        String scoreOutput,
        List<String> features,
        double center,
        double scale,
        Map<String, Object> metrics) {
}
