package com.sanica.risk.features;

/**
 * Per-account behaviour around one transaction, read in the same atomic step that records it.
 * The {@code prior*} statistics describe the account's history <em>before</em> this transaction.
 * {@code secondsSinceLastTx} is -1 for an account's first transaction.
 */
public record VelocitySnapshot(
        int txCount60s,
        int txCount1h,
        double amountSum1hUsd,
        int distinctCounterparties1h,
        boolean newDevice,
        boolean newCountry,
        long priorTxCount,
        double priorMeanUsd,
        double priorStdUsd,
        double secondsSinceLastTx) {
}
