package com.sanica.risk;

import com.sanica.risk.domain.Decision;
import com.sanica.risk.engine.ScoringPolicy;
import java.time.Duration;
import java.util.Map;

/** Twelve-factor configuration read from environment variables. */
public record Config(
        int port,
        String redisUrl,
        String inputStream,
        String decisionStream,
        String consumerGroup,
        String consumerName,
        Duration latencyBudget,
        Decision fallbackDecision,
        ScoringPolicy policy,
        String rulesPath,
        String ledgerUrl,
        Duration ledgerPollInterval,
        String adminToken,
        boolean demoMode) {

    public static Config fromEnv() {
        return fromMap(System.getenv());
    }

    public static Config fromMap(Map<String, String> env) {
        return new Config(
                Integer.parseInt(env.getOrDefault("PORT", "8081")),
                blankToNull(env.get("REDIS_URL")),
                env.getOrDefault("INPUT_STREAM", "ledger.transactions"),
                env.getOrDefault("DECISION_STREAM", "risk.decisions"),
                env.getOrDefault("CONSUMER_GROUP", "risk-scorer"),
                env.getOrDefault("CONSUMER_NAME", env.getOrDefault("HOSTNAME", "risk-1")),
                Duration.ofMillis(Long.parseLong(env.getOrDefault("LATENCY_BUDGET_MS", "50"))),
                Decision.valueOf(env.getOrDefault("FALLBACK_DECISION", "REVIEW")),
                new ScoringPolicy(
                        Integer.parseInt(env.getOrDefault("REVIEW_THRESHOLD", "50")),
                        Integer.parseInt(env.getOrDefault("BLOCK_THRESHOLD", "80")),
                        Integer.parseInt(env.getOrDefault("ML_WEIGHT", "40")),
                        Double.parseDouble(env.getOrDefault("ML_REASON_THRESHOLD", "0.5"))),
                blankToNull(env.get("RULES_PATH")),
                blankToNull(env.get("LEDGER_URL")),
                Duration.ofMillis(Long.parseLong(env.getOrDefault("LEDGER_POLL_MS", "1000"))),
                blankToNull(env.get("ADMIN_TOKEN")),
                Boolean.parseBoolean(env.getOrDefault("DEMO_MODE", "false")));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
