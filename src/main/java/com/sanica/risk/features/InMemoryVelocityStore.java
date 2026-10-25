package com.sanica.risk.features;

import com.sanica.risk.domain.TransactionEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Single-instance velocity store with exactly the semantics of {@code velocity.lua}. Used for local runs,
 * tests and offline feature generation; production uses {@link RedisVelocityStore} so that every replica
 * shares one view of each account.
 */
public final class InMemoryVelocityStore implements VelocityStore {

    private final Map<String, AccountState> accounts = new ConcurrentHashMap<>();

    @Override
    public VelocitySnapshot record(TransactionEvent event) {
        AccountState state = accounts.computeIfAbsent(event.accountId(), id -> new AccountState());
        synchronized (state) {
            return state.record(event);
        }
    }

    private static final class AccountState {
        private final List<Tx> recent = new ArrayList<>();
        private final Map<String, Long> counterparties = new HashMap<>();
        private final Set<String> devices = new HashSet<>();
        private final Set<String> countries = new HashSet<>();
        private long n;
        private double mean;
        private double m2;
        private Long last;

        VelocitySnapshot record(TransactionEvent event) {
            long now = event.timestamp().toEpochMilli();
            double amount = event.amountUsd();
            long hourAgo = now - HOUR_MS;
            long minuteAgo = now - MINUTE_MS;

            recent.removeIf(tx -> tx.ts <= hourAgo);
            counterparties.values().removeIf(ts -> ts <= hourAgo);

            long priorN = n;
            double priorMean = mean;
            double priorM2 = m2;
            Long priorLast = last;
            boolean duplicate = recent.stream().anyMatch(tx -> tx.id.equals(event.transactionId()));
            boolean newDevice = false;
            boolean newCountry = false;

            if (!duplicate) {
                String device = event.deviceId();
                if (device != null && !device.isEmpty()) {
                    newDevice = n > 0 && !devices.contains(device);
                    devices.add(device);
                }
                String country = event.country();
                if (country != null && !country.isEmpty()) {
                    newCountry = n > 0 && !countries.contains(country);
                    countries.add(country);
                }
                recent.add(new Tx(event.transactionId(), now, amount));
                String counterparty = event.counterpartyId();
                if (counterparty != null && !counterparty.isEmpty()) {
                    counterparties.merge(counterparty, now, Math::max);
                }
                n++;
                double delta = amount - mean;
                mean += delta / n;
                m2 += delta * (amount - mean);
                last = last == null ? now : Math.max(last, now);
            }

            int count60 = 0;
            int count1h = 0;
            double sum = 0;
            for (Tx tx : recent) {
                if (tx.ts > minuteAgo) {
                    count60++;
                }
                if (tx.ts > hourAgo) {
                    count1h++;
                    sum += tx.amountUsd;
                }
            }
            int distinct = (int) counterparties.values().stream().filter(ts -> ts > hourAgo).count();
            double std = priorN >= 2 ? Math.sqrt(priorM2 / (priorN - 1)) : 0;
            double since = priorLast == null ? -1 : Math.max(0, now - priorLast) / 1000.0;
            return new VelocitySnapshot(count60, count1h, sum, distinct, newDevice, newCountry,
                    priorN, priorMean, std, since);
        }
    }

    private record Tx(String id, long ts, double amountUsd) {
    }
}
