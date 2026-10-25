package com.sanica.risk.stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanica.risk.domain.TransactionEvent;
import com.sanica.risk.domain.ValidationException;
import com.sanica.risk.engine.DecisionService;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.StreamEntryID;
import redis.clients.jedis.UnifiedJedis;
import redis.clients.jedis.exceptions.JedisDataException;
import redis.clients.jedis.params.XClaimParams;
import redis.clients.jedis.params.XPendingParams;
import redis.clients.jedis.params.XReadGroupParams;
import redis.clients.jedis.resps.StreamEntry;
import redis.clients.jedis.resps.StreamPendingEntry;

/**
 * Consumes ledger transaction events from a Redis Stream with a consumer group, so the work is shared across
 * replicas and every event is processed at least once:
 * <ul>
 *   <li>an entry is acknowledged only after its decision is stored and published;</li>
 *   <li>entries left pending by a crashed replica are reclaimed with XAUTOCLAIM after {@code claimIdle};</li>
 *   <li>at-least-once is safe because scoring is idempotent per transaction id ({@link DecisionService});</li>
 *   <li>malformed events go to a dead-letter stream instead of blocking the group forever.</li>
 * </ul>
 */
public final class RedisStreamConsumer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RedisStreamConsumer.class);

    private final UnifiedJedis redis;
    private final ObjectMapper mapper;
    private final DecisionService decisions;
    private final RedisStreams streams;
    private final String stream;
    private final String group;
    private final String consumer;
    private final Duration claimIdle;
    private final AtomicLong processed = new AtomicLong();
    private final AtomicLong deadLettered = new AtomicLong();
    private volatile boolean running;
    private Thread worker;

    public RedisStreamConsumer(UnifiedJedis redis, ObjectMapper mapper, DecisionService decisions,
                               String stream, String group, String consumer, Duration claimIdle) {
        this.redis = redis;
        this.mapper = mapper;
        this.decisions = decisions;
        this.streams = new RedisStreams(redis, mapper);
        this.stream = stream;
        this.group = group;
        this.consumer = consumer;
        this.claimIdle = claimIdle;
    }

    public RedisStreamConsumer start() {
        createGroup();
        running = true;
        worker = Thread.ofVirtual().name("stream-consumer-" + consumer).start(this::run);
        log.info("Consuming {} as {}/{}", stream, group, consumer);
        return this;
    }

    private void createGroup() {
        try {
            // "$" would skip events published before the first replica started; "0" scores the backlog too.
            redis.xgroupCreate(stream, group, new StreamEntryID(0, 0), true);
        } catch (JedisDataException e) {
            if (!e.getMessage().startsWith("BUSYGROUP")) {
                throw e;
            }
        }
    }

    private void run() {
        long lastClaim = 0;
        while (running) {
            try {
                if (System.currentTimeMillis() - lastClaim > claimIdle.toMillis()) {
                    lastClaim = System.currentTimeMillis();
                    reclaimAbandoned();
                }
                pollOnce(Duration.ofMillis(500));
            } catch (RuntimeException e) {
                if (!running) {
                    return;
                }
                log.warn("Stream consumer error; retrying in 1s", e);
                sleep(1_000);
            }
        }
    }

    /** Reads and processes one batch of new entries. Returns how many were handled. */
    public int pollOnce(Duration block) {
        List<Map.Entry<String, List<StreamEntry>>> batches = redis.xreadGroup(group, consumer,
                XReadGroupParams.xReadGroupParams().count(100).block((int) block.toMillis()),
                Map.of(stream, StreamEntryID.XREADGROUP_UNDELIVERED_ENTRY));
        if (batches == null) {
            return 0;
        }
        int handled = 0;
        for (Map.Entry<String, List<StreamEntry>> batch : batches) {
            for (StreamEntry entry : batch.getValue()) {
                handle(entry);
                handled++;
            }
        }
        return handled;
    }

    /**
     * Takes over entries another consumer read but never acknowledged (it crashed or hung). Uses XPENDING + XCLAIM
     * rather than XAUTOCLAIM so it works on Redis 5 as well; XCLAIM re-checks the idle time atomically, so two
     * replicas reclaiming at once cannot both take the same entry.
     */
    public int reclaimAbandoned() {
        List<StreamPendingEntry> pending = redis.xpending(stream, group,
                XPendingParams.xPendingParams(new StreamEntryID(0, 0), StreamEntryID.MAXIMUM_ID, 100));
        StreamEntryID[] stale = pending.stream()
                .filter(p -> p.getIdleTime() >= claimIdle.toMillis() && !p.getConsumerName().equals(consumer))
                .map(StreamPendingEntry::getID)
                .toArray(StreamEntryID[]::new);
        if (stale.length == 0) {
            return 0;
        }
        List<StreamEntry> claimed = redis.xclaim(stream, group, consumer, claimIdle.toMillis(),
                XClaimParams.xClaimParams(), stale);
        int handled = 0;
        for (StreamEntry entry : claimed) {
            if (entry == null) {
                continue;   // trimmed from the stream while pending
            }
            handle(entry);
            handled++;
        }
        log.info("Reclaimed {} abandoned entries", handled);
        return handled;
    }

    private void handle(StreamEntry entry) {
        TransactionEvent event;
        try {
            event = mapper.readValue(entry.getFields().get(RedisStreams.PAYLOAD), TransactionEvent.class).validate();
        } catch (Exception e) {
            // Retrying cannot fix a malformed event; park it for inspection and move on.
            log.warn("Dead-lettering entry {}: {}", entry.getID(), e.getMessage());
            streams.add(stream + ".dlq", Map.of("entryId", entry.getID().toString(), "fields", entry.getFields(),
                    "error", String.valueOf(e.getMessage())));
            redis.xack(stream, group, entry.getID());
            deadLettered.incrementAndGet();
            return;
        }
        try {
            decisions.decide(event);
        } catch (ValidationException e) {
            log.warn("Rejected event {}: {}", entry.getID(), e.getMessage());
        }
        // Not acknowledged if decide() threw anything else: the entry stays pending and is retried via XAUTOCLAIM.
        redis.xack(stream, group, entry.getID());
        processed.incrementAndGet();
    }

    public long processed() {
        return processed.get();
    }

    public long deadLettered() {
        return deadLettered.get();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        running = false;
        if (worker != null) {
            worker.interrupt();
            try {
                worker.join(2_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
