package com.sanica.risk.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A money movement to score, as published by the ledger. {@code deviceId} and {@code country} are optional:
 * ledger transfers made server-to-server have neither, card payments usually have both.
 */
public record TransactionEvent(
        String transactionId,
        String accountId,
        String counterpartyId,
        BigDecimal amount,
        String currency,
        Channel channel,
        String deviceId,
        String country,
        Instant timestamp) {

    /** Rejects events that cannot be scored meaningfully. Returns {@code this} for chaining. */
    public TransactionEvent validate() {
        requireText(transactionId, "transactionId");
        requireText(accountId, "accountId");
        if (amount == null || amount.signum() <= 0) {
            throw new ValidationException("amount must be positive");
        }
        if (!Money.supported(currency)) {
            throw new ValidationException("currency must be one of USD, EUR, GBP, INR, JPY, CAD, AUD, CHF");
        }
        if (channel == null) {
            throw new ValidationException("channel is required");
        }
        if (timestamp == null) {
            throw new ValidationException("timestamp is required");
        }
        if (country != null && !country.matches("[A-Z]{2}")) {
            throw new ValidationException("country must be an ISO 3166-1 alpha-2 code");
        }
        return this;
    }

    public double amountUsd() {
        return Money.toUsd(amount, currency);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank() || value.length() > 128) {
            throw new ValidationException(name + " must be 1-128 characters");
        }
    }
}
