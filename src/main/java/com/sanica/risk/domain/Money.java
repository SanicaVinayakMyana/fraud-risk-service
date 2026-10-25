package com.sanica.risk.domain;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Normalises amounts to USD so that thresholds and velocity sums mean the same thing in every currency.
 * Reference rates are deliberately coarse: risk features need magnitude, not settlement precision.
 */
public final class Money {

    private static final Map<String, Double> USD_PER_UNIT = Map.of(
            "USD", 1.0,
            "EUR", 1.08,
            "GBP", 1.27,
            "INR", 0.012,
            "JPY", 0.0067,
            "CAD", 0.73,
            "AUD", 0.66,
            "CHF", 1.13);

    private Money() {
    }

    public static boolean supported(String currency) {
        return currency != null && USD_PER_UNIT.containsKey(currency);
    }

    public static double toUsd(BigDecimal amount, String currency) {
        Double rate = USD_PER_UNIT.get(currency);
        if (rate == null) {
            throw new ValidationException("Unsupported currency " + currency);
        }
        return amount.doubleValue() * rate;
    }
}
