package com.sanica.risk.ml;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanica.risk.features.Features;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.Optional;

/**
 * Isolation Forest trained in scikit-learn, exported to ONNX and executed in-process with ONNX Runtime.
 * No Python at serving time and no network hop: one inference takes tens of microseconds.
 * {@link OrtSession#run} is thread-safe, so one session serves every request thread.
 */
public final class OnnxAnomalyModel implements AnomalyModel {

    public static final String DEFAULT_MODEL = "/model/isolation_forest.onnx";
    public static final String DEFAULT_METADATA = "/model/model-metadata.json";

    private final OrtEnvironment env;
    private final OrtSession session;
    private final ModelMetadata metadata;

    private OnnxAnomalyModel(OrtEnvironment env, OrtSession session, ModelMetadata metadata) {
        this.env = env;
        this.session = session;
        this.metadata = metadata;
    }

    public static OnnxAnomalyModel loadDefault(ObjectMapper mapper) {
        return load(DEFAULT_MODEL, DEFAULT_METADATA, mapper);
    }

    public static OnnxAnomalyModel load(String modelResource, String metadataResource, ObjectMapper mapper) {
        ModelMetadata metadata;
        byte[] model;
        try (InputStream meta = resource(metadataResource); InputStream onnx = resource(modelResource)) {
            metadata = mapper.readValue(meta, ModelMetadata.class);
            model = onnx.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        // Training/serving skew guard: the model must have been trained on exactly the features we compute.
        if (!Features.MODEL_INPUTS.equals(metadata.features())) {
            throw new IllegalStateException("Model " + metadata.version() + " expects features " + metadata.features()
                    + " but the service computes " + Features.MODEL_INPUTS);
        }
        if (metadata.scale() <= 0) {
            throw new IllegalStateException("Model calibration scale must be positive");
        }
        try {
            OrtEnvironment env = OrtEnvironment.getEnvironment();
            OrtSession.SessionOptions options = new OrtSession.SessionOptions();
            options.setIntraOpNumThreads(1);
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
            return new OnnxAnomalyModel(env, env.createSession(model, options), metadata);
        } catch (OrtException e) {
            throw new IllegalStateException("Cannot load ONNX model " + modelResource, e);
        }
    }

    @Override
    public double score(Features features) {
        return calibrate(rawScore(features));
    }

    /** The Isolation Forest decision function: below 0 is an outlier. */
    public double rawScore(Features features) {
        float[][] input = {features.modelInput()};
        try (OnnxTensor tensor = OnnxTensor.createTensor(env, input);
             OrtSession.Result result = session.run(Map.of(metadata.inputName(), tensor))) {
            Optional<OnnxValue> scores = result.get(metadata.scoreOutput());
            Object value = scores.orElseThrow(() -> new IllegalStateException(
                    "Model has no output " + metadata.scoreOutput())).getValue();
            return value instanceof float[][] matrix ? matrix[0][0] : ((float[]) value)[0];
        } catch (OrtException e) {
            throw new IllegalStateException("ONNX inference failed", e);
        }
    }

    double calibrate(double raw) {
        return 1.0 / (1.0 + Math.exp((raw - metadata.center()) / metadata.scale()));
    }

    @Override
    public String version() {
        return metadata.version();
    }

    public ModelMetadata metadata() {
        return metadata;
    }

    @Override
    public void close() {
        try {
            session.close();
        } catch (OrtException e) {
            throw new IllegalStateException(e);
        }
    }

    private static InputStream resource(String name) {
        InputStream in = OnnxAnomalyModel.class.getResourceAsStream(name);
        if (in == null) {
            throw new IllegalStateException("Model resource " + name + " not found; run ml/train_isolation_forest.py");
        }
        return in;
    }
}
