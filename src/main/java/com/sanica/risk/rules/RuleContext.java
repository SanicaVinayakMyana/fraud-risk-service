package com.sanica.risk.rules;

import com.sanica.risk.domain.TransactionEvent;
import com.sanica.risk.features.Features;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** The values a rule condition can read: every feature, the model's score and a few event attributes. */
public final class RuleContext {

    public static final Set<String> NUMERIC_FIELDS = Set.of(
            "amountUsd", "txCount60s", "txCount1h", "amountSum1hUsd", "distinctCounterparties1h", "newDevice",
            "newCountry", "priorTxCount", "amountZScore", "secondsSinceLastTx", "hourOfDay", "mlScore");
    public static final Set<String> TEXT_FIELDS = Set.of("channel", "country", "currency");

    private final Map<String, Double> numbers;
    private final Map<String, String> texts;

    public RuleContext(TransactionEvent event, Features features, double mlScore) {
        this.numbers = new LinkedHashMap<>(features.asMap());
        numbers.put("mlScore", mlScore);
        this.texts = new LinkedHashMap<>();
        texts.put("channel", event.channel().name());
        texts.put("country", event.country());
        texts.put("currency", event.currency());
    }

    public double number(String field) {
        Double value = numbers.get(field);
        if (value == null) {
            throw new IllegalArgumentException("Unknown numeric field " + field);
        }
        return value;
    }

    /** May be null, e.g. {@code country} for a server-to-server transfer. */
    public String text(String field) {
        return texts.get(field);
    }

    /** Renders a field for a reason's evidence, e.g. {@code txCount60s=7} or {@code amountUsd=1250.00}. */
    public String describe(String field) {
        if (texts.containsKey(field)) {
            return field + "=" + texts.get(field);
        }
        double value = number(field);
        return value == Math.rint(value) && Math.abs(value) < 1e15
                ? field + "=" + (long) value
                : field + "=" + String.format("%.2f", value);
    }
}
