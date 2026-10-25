package com.sanica.risk.engine;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanica.risk.domain.RiskDecision;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import redis.clients.jedis.UnifiedJedis;
import redis.clients.jedis.params.SetParams;

/**
 * Decisions in Redis, shared by every replica. {@code SET NX} makes the first decision for a transaction win,
 * so two replicas racing on a redelivered event still agree on one answer.
 */
public final class RedisDecisionStore implements DecisionStore {

    private static final String RECENT = "risk:decisions:recent";
    private static final int RECENT_SIZE = 1_000;

    private final UnifiedJedis redis;
    private final ObjectMapper mapper;
    private final long ttlSeconds;

    public RedisDecisionStore(UnifiedJedis redis, ObjectMapper mapper, Duration ttl) {
        this.redis = redis;
        this.mapper = mapper;
        this.ttlSeconds = ttl.toSeconds();
    }

    @Override
    public RiskDecision saveIfAbsent(RiskDecision decision) {
        String json = write(decision);
        String stored = redis.set(key(decision.transactionId()), json, SetParams.setParams().nx().ex(ttlSeconds));
        if (!"OK".equals(stored)) {
            return find(decision.transactionId()).orElse(decision);
        }
        redis.lpush(RECENT, decision.transactionId());
        redis.ltrim(RECENT, 0, RECENT_SIZE - 1);
        return decision;
    }

    @Override
    public Optional<RiskDecision> find(String transactionId) {
        return Optional.ofNullable(redis.get(key(transactionId))).map(this::read);
    }

    @Override
    public List<RiskDecision> recent(int limit) {
        List<String> ids = redis.lrange(RECENT, 0, limit - 1L);
        if (ids.isEmpty()) {
            return List.of();
        }
        return redis.mget(ids.stream().map(RedisDecisionStore::key).toArray(String[]::new)).stream()
                .filter(json -> json != null)
                .map(this::read)
                .toList();
    }

    private static String key(String transactionId) {
        return "risk:decision:" + transactionId;
    }

    private String write(RiskDecision decision) {
        try {
            return mapper.writeValueAsString(decision);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private RiskDecision read(String json) {
        try {
            return mapper.readValue(json, RiskDecision.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Corrupt decision in Redis", e);
        }
    }
}
