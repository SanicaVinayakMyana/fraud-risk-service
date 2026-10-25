"""Train the Isolation Forest, export it to ONNX and verify the export.

Input:  ml/data/features.csv, written by `./gradlew exportTrainingData` from the service's own feature code.
Output: src/main/resources/model/isolation_forest.onnx and model-metadata.json, loaded by OnnxAnomalyModel.

The model is unsupervised: it is fitted on unlabelled traffic. The fraud labels from the simulator are used
only to report how well it separates fraud from normal behaviour on a held-out split.

Export: the generic skl2onnx converter emits several graph nodes per tree, which costs ~2 ms per inference for
200 trees. An Isolation Forest score only needs, per tree, the path length of the leaf a sample lands in, so this
script writes the whole forest as ONE TreeEnsembleRegressor whose leaf values are those path lengths (summed over
trees), followed by the closed-form score: decision = -2 ** (-sum / (n_trees * c(max_samples))) - offset.
The result is checked against scikit-learn's decision_function before it is written.

    uv run --with-requirements ml/requirements.txt ml/train_isolation_forest.py [features.csv] [output dir]
"""

import hashlib
import json
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

import numpy as np
import onnx
import onnxruntime as ort
import pandas as pd
from onnx import TensorProto, helper
from sklearn.ensemble import IsolationForest
from sklearn.ensemble._iforest import _average_path_length
from sklearn.metrics import average_precision_score, roc_auc_score, roc_curve

ROOT = Path(__file__).resolve().parent.parent
DATA = Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / "ml" / "data" / "features.csv"
OUT = Path(sys.argv[2]) if len(sys.argv) > 2 else ROOT / "src" / "main" / "resources" / "model"

FEATURES = [
    "log_amount_usd",
    "tx_count_60s",
    "tx_count_1h",
    "log_amount_sum_1h_usd",
    "distinct_counterparties_1h",
    "new_device",
    "new_country",
    "amount_zscore",
    "log_seconds_since_last_tx",
    "hour_sin",
    "hour_cos",
]
SEED = 42
PARAMS = {"n_estimators": 200, "max_samples": 512, "contamination": 0.01, "random_state": SEED}


def recall_at_fpr(y_true, scores, max_fpr):
    fpr, tpr, _ = roc_curve(y_true, scores)
    return float(tpr[fpr <= max_fpr].max())


def leaf_path_lengths(tree):
    """Depth of every node plus the expected remaining depth for the samples left in it (sklearn's formula)."""
    t = tree.tree_
    depth = np.zeros(t.node_count)
    for node in range(t.node_count):
        for child in (t.children_left[node], t.children_right[node]):
            if child != -1:
                depth[child] = depth[node] + 1
    return depth + _average_path_length(t.n_node_samples)


def to_onnx(model: IsolationForest, n_features: int) -> onnx.ModelProto:
    attrs = {k: [] for k in ("nodes_treeids", "nodes_nodeids", "nodes_featureids", "nodes_values", "nodes_modes",
                             "nodes_truenodeids", "nodes_falsenodeids", "target_treeids", "target_nodeids",
                             "target_ids", "target_weights")}
    for tree_id, (tree, features) in enumerate(zip(model.estimators_, model.estimators_features_)):
        t = tree.tree_
        lengths = leaf_path_lengths(tree)
        for node in range(t.node_count):
            leaf = t.children_left[node] == -1
            attrs["nodes_treeids"].append(tree_id)
            attrs["nodes_nodeids"].append(node)
            # Each tree may see the features in its own order; map back to the input column.
            attrs["nodes_featureids"].append(0 if leaf else int(features[t.feature[node]]))
            attrs["nodes_values"].append(0.0 if leaf else float(t.threshold[node]))
            attrs["nodes_modes"].append("LEAF" if leaf else "BRANCH_LEQ")
            attrs["nodes_truenodeids"].append(0 if leaf else int(t.children_left[node]))
            attrs["nodes_falsenodeids"].append(0 if leaf else int(t.children_right[node]))
            if leaf:
                attrs["target_treeids"].append(tree_id)
                attrs["target_nodeids"].append(node)
                attrs["target_ids"].append(0)
                attrs["target_weights"].append(float(lengths[node]))

    denominator = len(model.estimators_) * float(_average_path_length([model._max_samples])[0])
    nodes = [
        helper.make_node("TreeEnsembleRegressor", ["features"], ["path_sum"], domain="ai.onnx.ml",
                         n_targets=1, aggregate_function="SUM", post_transform="NONE", **attrs),
        helper.make_node("Div", ["path_sum", "denominator"], ["normalised"]),
        helper.make_node("Neg", ["normalised"], ["exponent"]),
        helper.make_node("Pow", ["two", "exponent"], ["anomaly"]),        # sklearn's score, in (0, 1]
        helper.make_node("Neg", ["anomaly"], ["score_samples"]),
        helper.make_node("Sub", ["score_samples", "offset"], ["scores"]),  # = decision_function
    ]
    constants = [
        helper.make_tensor("denominator", TensorProto.FLOAT, [], [denominator]),
        helper.make_tensor("two", TensorProto.FLOAT, [], [2.0]),
        helper.make_tensor("offset", TensorProto.FLOAT, [], [float(model.offset_)]),
    ]
    graph = helper.make_graph(
        nodes, "isolation_forest",
        [helper.make_tensor_value_info("features", TensorProto.FLOAT, [None, n_features])],
        [helper.make_tensor_value_info("scores", TensorProto.FLOAT, [None, 1])],
        initializer=constants)
    proto = helper.make_model(graph, opset_imports=[helper.make_opsetid("", 17), helper.make_opsetid("ai.onnx.ml", 3)],
                              producer_name="fraud-risk-service")
    proto.ir_version = 8
    onnx.checker.check_model(proto)
    return proto


def main():
    df = pd.read_csv(DATA)
    header = list(df.columns[: len(FEATURES)])
    if header != FEATURES:
        sys.exit(f"Feature columns {header} do not match {FEATURES}; re-run exportTrainingData")

    rng = np.random.default_rng(SEED)
    test_mask = rng.random(len(df)) < 0.2
    train, test = df[~test_mask], df[test_mask]
    x_train = train[FEATURES].to_numpy(np.float32)
    x_test = test[FEATURES].to_numpy(np.float32)
    y_test = test["is_fraud"].to_numpy()

    model = IsolationForest(**PARAMS, n_jobs=-1).fit(x_train)

    # decision_function: < 0 means outlier. Negate it so that higher = more anomalous for the metrics.
    test_decision = model.decision_function(x_test)
    anomaly = -test_decision
    metrics = {
        "rocAuc": round(float(roc_auc_score(y_test, anomaly)), 4),
        "prAuc": round(float(average_precision_score(y_test, anomaly)), 4),
        "recallAt1PctFpr": round(recall_at_fpr(y_test, anomaly, 0.01), 4),
        "recallAt5PctFpr": round(recall_at_fpr(y_test, anomaly, 0.05), 4),
        "trainRows": int(len(train)),
        "testRows": int(len(test)),
        "testFraudRate": round(float(y_test.mean()), 5),
        "flaggedShareByScenario": {
            scenario: round(float((model.decision_function(group[FEATURES].to_numpy(np.float32)) < 0).mean()), 4)
            for scenario, group in test.groupby("scenario")
        },
    }

    payload = to_onnx(model, len(FEATURES)).SerializeToString()

    # The export must reproduce scikit-learn before it ships.
    session = ort.InferenceSession(payload, providers=["CPUExecutionProvider"])
    onnx_scores = session.run(["scores"], {"features": x_test})[0].ravel()
    max_error = float(np.abs(onnx_scores - test_decision).max())
    if max_error > 1e-4:
        sys.exit(f"ONNX export differs from scikit-learn by up to {max_error}")
    one_row = x_test[:1]
    for _ in range(200):
        session.run(["scores"], {"features": one_row})
    start = time.perf_counter()
    for i in range(2_000):
        session.run(["scores"], {"features": x_test[i:i + 1]})
    single_row_us = (time.perf_counter() - start) / 2_000 * 1e6

    # Logistic calibration: the contamination threshold maps to 0.5, half a spread of the decision function to ~0.73.
    scale = float(np.std(model.decision_function(x_train)) / 2)

    digest = hashlib.sha256(payload).hexdigest()[:8]
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / "isolation_forest.onnx").write_bytes(payload)
    metadata = {
        "version": f"iforest-{datetime.now(timezone.utc):%Y%m%d}-{digest}",
        "inputName": "features",
        "scoreOutput": "scores",
        "features": FEATURES,
        "center": 0.0,
        "scale": scale,
        "metrics": {**metrics, "onnxMaxAbsError": max_error, "onnxSingleRowMicros": round(single_row_us, 1)},
        "params": PARAMS,
        "trainedAt": datetime.now(timezone.utc).isoformat(timespec="seconds"),
    }
    (OUT / "model-metadata.json").write_text(json.dumps(metadata, indent=2) + "\n")
    print(json.dumps(metadata, indent=2))


if __name__ == "__main__":
    main()
