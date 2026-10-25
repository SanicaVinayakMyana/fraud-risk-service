package com.sanica.risk.features;

import com.sanica.risk.domain.TransactionEvent;

/**
 * Sliding-window counters per account. Implementations must record the event and read the windows atomically,
 * so concurrent scorers for the same account never both see "4 transactions" and both let a 5th through.
 * Windows are relative to the event's own timestamp, which makes replays and backfills deterministic.
 * Recording the same transaction id twice within the hour does not count it twice.
 */
public interface VelocityStore extends AutoCloseable {

    long MINUTE_MS = 60_000;
    long HOUR_MS = 3_600_000;

    VelocitySnapshot record(TransactionEvent event);

    @Override
    default void close() {
    }
}
