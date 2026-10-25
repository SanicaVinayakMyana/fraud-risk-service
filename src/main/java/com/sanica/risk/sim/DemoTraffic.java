package com.sanica.risk.sim;

import com.sanica.risk.domain.TransactionEvent;
import com.sanica.risk.sim.TrafficSimulator.Profile;
import com.sanica.risk.sim.TrafficSimulator.Scenario;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Live demo traffic: a steady trickle of normal payments plus an attack every so often, so the dashboard and the
 * shadow report have something to show. Customers get a day of history first (fed straight into the velocity
 * store, not scored) so that the demo starts from established behaviour rather than a population of new accounts.
 */
public final class DemoTraffic implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DemoTraffic.class);
    private static final Scenario[] ATTACKS = {
            Scenario.CARD_TESTING, Scenario.ACCOUNT_TAKEOVER, Scenario.MULE_FAN_OUT, Scenario.AMOUNT_SPIKE};

    private final TrafficSimulator simulator;
    private final Random random;
    private final Consumer<TransactionEvent> sink;
    private final Clock clock;
    private final List<Profile> profiles = new ArrayList<>();
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("demo-traffic").factory());

    public DemoTraffic(long seed, int customers, Consumer<TransactionEvent> sink, Consumer<TransactionEvent> warmUp,
                       Clock clock) {
        this.simulator = new TrafficSimulator(seed);
        this.random = new Random(seed);
        this.sink = sink;
        this.clock = clock;
        Instant now = clock.instant();
        for (int i = 0; i < customers; i++) {
            Profile profile = simulator.newProfile(String.format("demo-%04d", i));
            profiles.add(profile);
            for (int k = 0; k < 8; k++) {
                warmUp.accept(simulator.normalEvent(profile, now.minusSeconds(3_600 + random.nextInt(86_400))));
            }
        }
    }

    public DemoTraffic start(Duration normalInterval, Duration attackInterval) {
        scheduler.scheduleAtFixedRate(this::normal, 0, normalInterval.toMillis(), TimeUnit.MILLISECONDS);
        scheduler.scheduleAtFixedRate(() -> attack(ATTACKS[random.nextInt(ATTACKS.length)]),
                attackInterval.toMillis(), attackInterval.toMillis(), TimeUnit.MILLISECONDS);
        log.info("Demo traffic on: {} customers, a payment every {} ms, an attack every {} s", profiles.size(),
                normalInterval.toMillis(), attackInterval.toSeconds());
        return this;
    }

    private synchronized void normal() {
        safely(() -> sink.accept(simulator.normalEvent(profiles.get(random.nextInt(profiles.size())), clock.instant())));
    }

    /** Plays an attack in real time: each event is sent when its timestamp comes due. Returns the account attacked. */
    public synchronized String attack(Scenario scenario) {
        Profile victim = profiles.get(random.nextInt(profiles.size()));
        Instant start = clock.instant();
        List<TransactionEvent> events = simulator.attack(scenario, victim, start);
        log.info("Demo attack {} on {} ({} events)", scenario, victim.accountId(), events.size());
        for (TransactionEvent event : events) {
            long delay = Math.min(Duration.between(start, event.timestamp()).toMillis(), 20_000);
            scheduler.schedule(() -> safely(() -> sink.accept(event)), Math.max(0, delay / 4), TimeUnit.MILLISECONDS);
        }
        return victim.accountId();
    }

    private static void safely(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            log.warn("Demo traffic event failed: {}", e.getMessage());
        }
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }
}
