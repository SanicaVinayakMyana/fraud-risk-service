package com.sanica.risk.features;

import com.sanica.risk.domain.TransactionEvent;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything the rules and the model know about a transaction. The same code builds features for live scoring
 * and for the offline training set, so the model never sees a feature computed differently from production.
 */
public record Features(
        double amountUsd,
        int txCount60s,
        int txCount1h,
        double amountSum1hUsd,
        int distinctCounterparties1h,
        boolean newDevice,
        boolean newCountry,
        long priorTxCount,
        double amountZScore,
        double secondsSinceLastTx,
        int hourOfDay) {

    /** Model input order. The ONNX model's metadata must list exactly these names, or the service refuses to start. */
    public static final List<String> MODEL_INPUTS = List.of(
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
            "hour_cos");

    /** Too little history makes a z-score meaningless; below this the feature is 0. */
    static final int MIN_HISTORY_FOR_ZSCORE = 5;
    private static final double NO_PREVIOUS_TX_SECONDS = 30 * 86_400;

    public static Features of(TransactionEvent event, VelocitySnapshot velocity) {
        double amount = event.amountUsd();
        double z = 0;
        if (velocity.priorTxCount() >= MIN_HISTORY_FOR_ZSCORE) {
            // A floor on the spread stops a perfectly regular account from turning a cent of change into z = 1000.
            double spread = Math.max(velocity.priorStdUsd(), Math.max(1.0, velocity.priorMeanUsd() * 0.1));
            z = Math.max(-20, Math.min(20, (amount - velocity.priorMeanUsd()) / spread));
        }
        return new Features(
                amount,
                velocity.txCount60s(),
                velocity.txCount1h(),
                velocity.amountSum1hUsd(),
                velocity.distinctCounterparties1h(),
                velocity.newDevice(),
                velocity.newCountry(),
                velocity.priorTxCount(),
                z,
                velocity.secondsSinceLastTx(),
                event.timestamp().atZone(ZoneOffset.UTC).getHour());
    }

    public float[] modelInput() {
        double seconds = secondsSinceLastTx < 0
                ? NO_PREVIOUS_TX_SECONDS
                : Math.min(secondsSinceLastTx, NO_PREVIOUS_TX_SECONDS);
        double angle = 2 * Math.PI * hourOfDay / 24.0;
        return new float[] {
                (float) Math.log1p(amountUsd),
                txCount60s,
                txCount1h,
                (float) Math.log1p(amountSum1hUsd),
                distinctCounterparties1h,
                newDevice ? 1 : 0,
                newCountry ? 1 : 0,
                (float) amountZScore,
                (float) Math.log1p(seconds),
                (float) Math.sin(angle),
                (float) Math.cos(angle)
        };
    }

    /** Named numeric values that rules can reference. Booleans are 1 or 0. */
    public Map<String, Double> asMap() {
        Map<String, Double> values = new LinkedHashMap<>();
        values.put("amountUsd", amountUsd);
        values.put("txCount60s", (double) txCount60s);
        values.put("txCount1h", (double) txCount1h);
        values.put("amountSum1hUsd", amountSum1hUsd);
        values.put("distinctCounterparties1h", (double) distinctCounterparties1h);
        values.put("newDevice", newDevice ? 1.0 : 0.0);
        values.put("newCountry", newCountry ? 1.0 : 0.0);
        values.put("priorTxCount", (double) priorTxCount);
        values.put("amountZScore", amountZScore);
        values.put("secondsSinceLastTx", secondsSinceLastTx);
        values.put("hourOfDay", (double) hourOfDay);
        return values;
    }
}
