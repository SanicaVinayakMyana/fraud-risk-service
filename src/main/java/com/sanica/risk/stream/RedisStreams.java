package com.sanica.risk.stream;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanica.risk.domain.RiskDecision;
import com.sanica.risk.domain.TransactionEvent;
import java.util.Map;
import redis.clients.jedis.StreamEntryID;
import redis.clients.jedis.UnifiedJedis;
import redis.clients.jedis.params.XAddParams;

/**
 * Writes to Redis Streams. Each entry has a single {@code payload} field holding JSON, so producers in any
 * language only need XADD. Streams are capped (approximately, which is O(1)) so they cannot grow without bound.
 */
public final class RedisStreams {

    public static final String PAYLOAD = "payload";
    private static final long MAX_LENGTH = 1_000_000;

    private final UnifiedJedis redis;
    private final ObjectMapper mapper;

    public RedisStreams(UnifiedJedis redis, ObjectMapper mapper) {
        this.redis = redis;
        this.mapper = mapper;
    }

    public StreamEntryID publishEvent(String stream, TransactionEvent event) {
        return add(stream, event);
    }

    public StreamEntryID publishDecision(String stream, RiskDecision decision) {
        return add(stream, decision);
    }

    public StreamEntryID add(String stream, Object value) {
        try {
            return redis.xadd(stream, XAddParams.xAddParams().maxLen(MAX_LENGTH).approximateTrimming(),
                    Map.of(PAYLOAD, mapper.writeValueAsString(value)));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
