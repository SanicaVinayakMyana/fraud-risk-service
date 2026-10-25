package com.sanica.risk.sim;

import com.sanica.risk.domain.Channel;
import com.sanica.risk.domain.Money;
import com.sanica.risk.domain.TransactionEvent;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * Generates realistic payment traffic with labelled fraud: everyday card and transfer behaviour per account,
 * plus the four attack patterns the rules and model are meant to catch. Seeded, so runs are reproducible.
 * Labels are used only to <em>evaluate</em> the model; the Isolation Forest itself never sees them.
 */
public final class TrafficSimulator {

    public enum Scenario {
        NORMAL,
        /** Many tiny card payments in a minute from a fresh device: checking which stolen card numbers work. */
        CARD_TESTING,
        /** New device and new country, then a large transfer to a new payee. */
        ACCOUNT_TAKEOVER,
        /** Money split across many new payees within the hour: a mule account cashing out. */
        MULE_FAN_OUT,
        /** One transfer far above anything the account has sent before. */
        AMOUNT_SPIKE
    }

    public record LabeledEvent(TransactionEvent event, Scenario scenario) {
        public boolean fraud() {
            return scenario != Scenario.NORMAL;
        }
    }

    /** One simulated customer's habits. */
    public static final class Profile {
        final String accountId;
        final String currency;
        final String homeCountry;
        final List<String> devices = new ArrayList<>();
        final List<String> payees = new ArrayList<>();
        final List<String> merchants = new ArrayList<>();
        final double medianUsd;
        final double perDay;
        final double cardShare;

        Profile(String accountId, String currency, String homeCountry, double medianUsd, double perDay, double cardShare) {
            this.accountId = accountId;
            this.currency = currency;
            this.homeCountry = homeCountry;
            this.medianUsd = medianUsd;
            this.perDay = perDay;
            this.cardShare = cardShare;
        }

        public String accountId() {
            return accountId;
        }
    }

    private static final String[] CURRENCIES = {"USD", "USD", "EUR", "EUR", "GBP", "INR"};
    private static final String[] HOME = {"US", "US", "DE", "FR", "GB", "IN"};
    private static final String[] ELSEWHERE = {"BR", "NG", "RU", "VN", "UA", "PH", "RO", "ID", "MX", "TR"};
    private static final String[] TRAVEL = {"ES", "IT", "NL", "CA", "JP", "AE", "SG", "PT"};

    private final Random random;

    public TrafficSimulator(long seed) {
        this.random = new Random(seed);
    }

    public Profile newProfile(String accountId) {
        int home = random.nextInt(CURRENCIES.length);
        Profile p = new Profile(accountId, CURRENCIES[home], HOME[home],
                Math.exp(2.8 + random.nextGaussian() * 0.7),       // median spend around 16 USD, 4-70 typical
                2 + random.nextDouble() * 8,
                0.5 + random.nextDouble() * 0.4);
        p.devices.add(id("dev"));
        if (random.nextDouble() < 0.4) {
            p.devices.add(id("dev"));
        }
        for (int i = 0, n = 4 + random.nextInt(8); i < n; i++) {
            p.payees.add(id("acct"));
        }
        for (int i = 0, n = 6 + random.nextInt(10); i < n; i++) {
            p.merchants.add("m-" + random.nextInt(500));
        }
        return p;
    }

    /** Traffic for {@code accounts} customers over {@code span}, with fraud injected into a share of them. */
    public List<LabeledEvent> generate(int accounts, Instant start, Duration span, double fraudAccountShare) {
        List<LabeledEvent> events = new ArrayList<>();
        Scenario[] attacks = {Scenario.CARD_TESTING, Scenario.ACCOUNT_TAKEOVER, Scenario.MULE_FAN_OUT, Scenario.AMOUNT_SPIKE};
        for (int a = 0; a < accounts; a++) {
            Profile p = newProfile(id("acct"));
            long days = Math.max(1, span.toDays());
            for (long d = 0; d < days; d++) {
                Instant day = start.plus(Duration.ofDays(d));
                int count = poisson(p.perDay);
                for (int i = 0; i < count; i++) {
                    events.add(new LabeledEvent(normalEvent(p, day.plusSeconds(secondOfDay())), Scenario.NORMAL));
                }
            }
            if (random.nextDouble() < fraudAccountShare) {
                Scenario scenario = attacks[random.nextInt(attacks.length)];
                // Attacks happen after the account has some history, as they do in practice.
                Instant when = start.plus(Duration.ofSeconds((long) (span.toSeconds() * (0.5 + random.nextDouble() * 0.45))));
                attack(scenario, p, when).forEach(e -> events.add(new LabeledEvent(e, scenario)));
            }
        }
        events.sort(Comparator.comparing(e -> e.event().timestamp()));
        return events;
    }

    public TransactionEvent normalEvent(Profile p, Instant at) {
        boolean card = random.nextDouble() < p.cardShare;
        double usd = p.medianUsd * Math.exp(random.nextGaussian() * 0.6);
        if (random.nextDouble() < 0.02) {
            usd *= 4 + random.nextDouble() * 4;      // rent, a flight: legitimate but unusual
        }
        String device = pick(p.devices);
        if (random.nextDouble() < 0.02) {
            device = id("dev");                       // a new phone
            p.devices.add(device);
        }
        String country = random.nextDouble() < 0.02 ? TRAVEL[random.nextInt(TRAVEL.length)] : p.homeCountry;
        String counterparty = card ? pick(p.merchants) : pick(p.payees);
        return event(p, counterparty, usd, card ? Channel.CARD : Channel.TRANSFER, device, country, at);
    }

    /** The events of one attack starting at {@code at}, in time order. */
    public List<TransactionEvent> attack(Scenario scenario, Profile p, Instant at) {
        List<TransactionEvent> events = new ArrayList<>();
        switch (scenario) {
            case CARD_TESTING -> {
                String device = id("dev");
                String country = ELSEWHERE[random.nextInt(ELSEWHERE.length)];
                Instant t = at;
                for (int i = 0, n = 8 + random.nextInt(13); i < n; i++) {
                    events.add(event(p, "m-" + random.nextInt(5_000), 0.5 + random.nextDouble() * 4.5,
                            Channel.CARD, device, country, t));
                    t = t.plusMillis(1_500 + random.nextInt(4_000));
                }
            }
            case ACCOUNT_TAKEOVER -> {
                String device = id("dev");
                String country = ELSEWHERE[random.nextInt(ELSEWHERE.length)];
                Instant t = at;
                for (int i = 0, n = 1 + random.nextInt(3); i < n; i++) {
                    double usd = Math.max(1_200, p.medianUsd * (25 + random.nextDouble() * 40));
                    events.add(event(p, id("acct"), usd, Channel.TRANSFER, device, country, t));
                    t = t.plusSeconds(60 + random.nextInt(600));
                }
            }
            case MULE_FAN_OUT -> {
                String device = pick(p.devices);
                Instant t = at;
                for (int i = 0, n = 10 + random.nextInt(6); i < n; i++) {
                    events.add(event(p, id("acct"), 150 + random.nextDouble() * 700, Channel.TRANSFER, device,
                            p.homeCountry, t));
                    t = t.plusSeconds(30 + random.nextInt(200));
                }
            }
            case AMOUNT_SPIKE -> events.add(event(p, id("acct"), Math.max(900, p.medianUsd * (40 + random.nextDouble() * 60)),
                    Channel.TRANSFER, pick(p.devices), p.homeCountry, at));
            case NORMAL -> events.add(normalEvent(p, at));
        }
        return events;
    }

    private TransactionEvent event(Profile p, String counterparty, double usd, Channel channel, String device,
                                   String country, Instant at) {
        BigDecimal local = BigDecimal.valueOf(usd / Money.toUsd(BigDecimal.ONE, p.currency))
                .setScale(2, RoundingMode.HALF_EVEN)
                .max(new BigDecimal("0.01"));
        return new TransactionEvent(UUID.randomUUID().toString(), p.accountId, counterparty, local, p.currency,
                channel, device, country, at);
    }

    /** Mostly daytime and evening; a few night-owl payments. */
    private long secondOfDay() {
        double hour = random.nextDouble() < 0.93 ? 7 + random.nextDouble() * 16 : random.nextDouble() * 7;
        return (long) (hour * 3_600);
    }

    private int poisson(double mean) {
        double l = Math.exp(-mean);
        double p = 1;
        int k = 0;
        do {
            k++;
            p *= random.nextDouble();
        } while (p > l);
        return k - 1;
    }

    private String pick(List<String> values) {
        return values.get(random.nextInt(values.size()));
    }

    private String id(String prefix) {
        return prefix + "-" + Long.toHexString(random.nextLong() & 0xffffffffffL);
    }
}
