package com.sanica.risk.features;

import com.sanica.risk.domain.TransactionEvent;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import redis.clients.jedis.UnifiedJedis;
import redis.clients.jedis.exceptions.JedisNoScriptException;

/**
 * Velocity windows in Redis. One EVALSHA per event records the transaction and reads every window atomically
 * (Lua scripts run without interleaving), so the check-then-act race between replicas cannot happen.
 * All keys of an account share the hash tag {@code {accountId}} and therefore one Redis Cluster slot.
 */
public final class RedisVelocityStore implements VelocityStore {

    private static final String SCRIPT = loadScript();

    private final UnifiedJedis redis;
    private final long historyTtlSeconds;
    private volatile String sha;

    public RedisVelocityStore(UnifiedJedis redis, Duration historyTtl) {
        this.redis = redis;
        this.historyTtlSeconds = historyTtl.toSeconds();
        this.sha = redis.scriptLoad(SCRIPT);
    }

    @Override
    public VelocitySnapshot record(TransactionEvent event) {
        String tag = "risk:{" + event.accountId() + "}:";
        List<String> keys = List.of(tag + "tx", tag + "amt", tag + "cp", tag + "dev", tag + "ctry", tag + "stats");
        List<String> args = List.of(
                event.transactionId(),
                Long.toString(event.timestamp().toEpochMilli()),
                Double.toString(event.amountUsd()),
                nullToEmpty(event.counterpartyId()),
                nullToEmpty(event.deviceId()),
                nullToEmpty(event.country()),
                Long.toString(historyTtlSeconds));
        Object reply;
        try {
            reply = redis.evalsha(sha, keys, args);
        } catch (JedisNoScriptException e) {
            // Redis restarted or was flushed and lost its script cache.
            sha = redis.scriptLoad(SCRIPT);
            reply = redis.evalsha(sha, keys, args);
        }
        List<?> r = (List<?>) reply;
        return new VelocitySnapshot(
                (int) asLong(r.get(0)),
                (int) asLong(r.get(1)),
                asDouble(r.get(2)),
                (int) asLong(r.get(3)),
                asLong(r.get(4)) == 1,
                asLong(r.get(5)) == 1,
                asLong(r.get(6)),
                asDouble(r.get(7)),
                asDouble(r.get(8)),
                asDouble(r.get(9)));
    }

    private static long asLong(Object value) {
        return value instanceof Long l ? l : Long.parseLong(text(value));
    }

    private static double asDouble(Object value) {
        return Double.parseDouble(text(value));
    }

    private static String text(Object value) {
        return value instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8) : String.valueOf(value);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String loadScript() {
        try (InputStream in = RedisVelocityStore.class.getResourceAsStream("/velocity.lua")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
