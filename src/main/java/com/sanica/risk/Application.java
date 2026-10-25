package com.sanica.risk;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanica.risk.api.Json;
import com.sanica.risk.api.RiskApi;
import com.sanica.risk.domain.RiskDecision;
import com.sanica.risk.domain.TransactionEvent;
import com.sanica.risk.engine.DecisionService;
import com.sanica.risk.engine.DecisionStore;
import com.sanica.risk.engine.InMemoryDecisionStore;
import com.sanica.risk.engine.RedisDecisionStore;
import com.sanica.risk.engine.RiskEngine;
import com.sanica.risk.features.InMemoryVelocityStore;
import com.sanica.risk.features.RedisVelocityStore;
import com.sanica.risk.features.VelocityStore;
import com.sanica.risk.ml.OnnxAnomalyModel;
import com.sanica.risk.rules.RuleParser;
import com.sanica.risk.rules.RuleRepository;
import com.sanica.risk.rules.RuleSet;
import com.sanica.risk.sim.DemoTraffic;
import com.sanica.risk.stream.LedgerPoller;
import com.sanica.risk.stream.RedisStreamConsumer;
import com.sanica.risk.stream.RedisStreams;
import io.javalin.Javalin;
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.UnifiedJedis;

/**
 * Wires the components together and owns their lifecycle.
 *
 * <p>With {@code REDIS_URL} set, velocity state and decisions live in Redis, transactions arrive on a Redis Stream
 * and decisions are published to another, so any number of replicas can run side by side. Without it everything
 * is in-process, which is what {@code ./gradlew runLocal} and the tests use.
 */
public final class Application implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(Application.class);

    private final Javalin server;
    private final List<AutoCloseable> owned;

    private Application(Javalin server, List<AutoCloseable> owned) {
        this.server = server;
        this.owned = owned;
    }

    public static void main(String[] args) {
        Application app = start(Config.fromEnv());
        Runtime.getRuntime().addShutdownHook(new Thread(app::close, "shutdown"));
    }

    public static Application start(Config config) {
        ObjectMapper mapper = Json.mapper();
        List<AutoCloseable> owned = new ArrayList<>();
        PrometheusMeterRegistry metrics = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        new JvmMemoryMetrics().bindTo(metrics);
        JvmGcMetrics gc = new JvmGcMetrics();
        gc.bindTo(metrics);
        owned.add(gc);

        UnifiedJedis redis = config.redisUrl() == null ? null : new JedisPooled(URI.create(config.redisUrl()), 1_000);
        if (redis != null) {
            owned.add(redis);
        }
        VelocityStore velocity = redis == null
                ? new InMemoryVelocityStore()
                : new RedisVelocityStore(redis, Duration.ofDays(90));
        OnnxAnomalyModel model = OnnxAnomalyModel.loadDefault(mapper);
        owned.add(model);

        RuleParser parser = new RuleParser(mapper);
        RuleSet ruleSet = config.rulesPath() == null
                ? parser.parseClasspath("/rules.json")
                : parser.parseFile(Path.of(config.rulesPath()));
        RuleRepository rules = new RuleRepository(ruleSet);

        RiskEngine engine = new RiskEngine(velocity, model, rules, config.policy(), metrics, config.latencyBudget(),
                config.fallbackDecision(), Clock.systemUTC());
        owned.add(engine);

        RedisStreams streams = redis == null ? null : new RedisStreams(redis, mapper);
        DecisionStore store = redis == null
                ? new InMemoryDecisionStore(50_000)
                : new RedisDecisionStore(redis, mapper, Duration.ofDays(7));
        Consumer<RiskDecision> publish = redis == null
                ? decision -> { }
                : decision -> streams.publishDecision(config.decisionStream(), decision);
        DecisionService decisions = new DecisionService(engine, store, publish);

        // Where asynchronous transactions go: onto the stream when there is one, straight to scoring otherwise.
        Consumer<TransactionEvent> ingest = redis == null
                ? decisions::decide
                : event -> streams.publishEvent(config.inputStream(), event);

        if (redis != null) {
            owned.add(new RedisStreamConsumer(redis, mapper, decisions, config.inputStream(), config.consumerGroup(),
                    config.consumerName(), Duration.ofSeconds(30)).start());
        }
        if (config.ledgerUrl() != null) {
            owned.add(new LedgerPoller(config.ledgerUrl(), mapper, ingest).start(config.ledgerPollInterval()));
        }
        DemoTraffic demo = null;
        if (config.demoMode()) {
            demo = new DemoTraffic(7L, 200, ingest, velocity::record, Clock.systemUTC())
                    .start(Duration.ofMillis(400), Duration.ofSeconds(25));
            owned.add(demo);
        }

        Javalin server = RiskApi.create(decisions, store, engine, rules, model.metadata(), metrics, mapper,
                config.adminToken(), demo).start(config.port());
        log.info("Risk service on port {}: model {}, rules {}, state in {}, latency budget {} ms", server.port(),
                model.version(), ruleSet.version(), redis == null ? "memory" : "Redis",
                config.latencyBudget().toMillis());
        return new Application(server, owned);
    }

    public int port() {
        return server.port();
    }

    @Override
    public void close() {
        server.stop();
        // Stop producers before the things they feed.
        for (int i = owned.size() - 1; i >= 0; i--) {
            try {
                owned.get(i).close();
            } catch (Exception e) {
                log.warn("Failed to close {}", owned.get(i).getClass().getSimpleName(), e);
            }
        }
    }
}
