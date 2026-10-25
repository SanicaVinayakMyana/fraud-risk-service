package com.sanica.risk.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanica.risk.domain.TransactionEvent;
import com.sanica.risk.domain.ValidationException;
import com.sanica.risk.engine.DecisionService;
import com.sanica.risk.engine.DecisionStore;
import com.sanica.risk.engine.RiskEngine;
import com.sanica.risk.ml.ModelMetadata;
import com.sanica.risk.rules.Rule;
import com.sanica.risk.rules.RuleMode;
import com.sanica.risk.rules.RuleRepository;
import com.sanica.risk.sim.DemoTraffic;
import com.sanica.risk.sim.TrafficSimulator.Scenario;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.staticfiles.Location;
import io.javalin.json.JavalinJackson;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** HTTP API. The ledger calls {@code POST /v1/score} synchronously before booking a payment. */
public final class RiskApi {

    private static final Logger log = LoggerFactory.getLogger(RiskApi.class);
    private static final int MAX_PAGE = 500;

    public record ErrorResponse(String error, String message) {
    }

    public record ModeRequest(RuleMode mode) {
    }

    public record RuleView(String id, String description, String reasonCode, int points, String action,
                           RuleMode mode, List<String> fields) {

        static RuleView from(Rule rule) {
            return new RuleView(rule.id(), rule.description(), rule.reasonCode(), rule.points(),
                    rule.action() == null ? null : rule.action().name(), rule.mode(),
                    List.copyOf(rule.when().fields()));
        }
    }

    public record RulesView(String version, List<RuleView> rules) {
    }

    private final DecisionService decisions;
    private final DecisionStore store;
    private final RiskEngine engine;
    private final RuleRepository rules;
    private final ModelMetadata model;
    private final PrometheusMeterRegistry metrics;
    private final ObjectMapper mapper;
    private final String adminToken;
    private final DemoTraffic demo;

    private RiskApi(DecisionService decisions, DecisionStore store, RiskEngine engine, RuleRepository rules,
                    ModelMetadata model, PrometheusMeterRegistry metrics, ObjectMapper mapper, String adminToken,
                    DemoTraffic demo) {
        this.decisions = decisions;
        this.store = store;
        this.engine = engine;
        this.rules = rules;
        this.model = model;
        this.metrics = metrics;
        this.mapper = mapper;
        this.adminToken = adminToken;
        this.demo = demo;
    }

    public static Javalin create(DecisionService decisions, DecisionStore store, RiskEngine engine,
                                 RuleRepository rules, ModelMetadata model, PrometheusMeterRegistry metrics,
                                 ObjectMapper mapper, String adminToken, DemoTraffic demo) {
        RiskApi api = new RiskApi(decisions, store, engine, rules, model, metrics, mapper, adminToken, demo);
        Javalin app = Javalin.create(config -> {
            config.showJavalinBanner = false;
            config.useVirtualThreads = true;
            config.jsonMapper(new JavalinJackson(mapper, true));
            config.staticFiles.add("/public", Location.CLASSPATH);
        });

        app.get("/health", ctx -> ctx.json(Map.of("status", "UP", "model", engine.modelVersion(),
                "rules", rules.current().version())));
        app.get("/metrics", ctx -> ctx.contentType("text/plain; version=0.0.4").result(metrics.scrape()));
        app.post("/v1/score", api::score);
        app.get("/v1/decisions", api::recentDecisions);
        app.get("/v1/decisions/{transactionId}", api::getDecision);
        app.get("/v1/rules", api::listRules);
        app.put("/v1/rules/{id}/mode", api::setRuleMode);
        app.get("/v1/shadow/report", ctx -> ctx.json(engine.shadowReport()));
        app.get("/v1/model", ctx -> ctx.json(model));
        app.get("/v1/demo", ctx -> ctx.json(Map.of("enabled", demo != null)));
        app.post("/v1/demo/attacks/{scenario}", api::demoAttack);

        app.exception(ValidationException.class, (e, ctx) ->
                ctx.status(400).json(new ErrorResponse("VALIDATION_FAILED", e.getMessage())));
        app.exception(Exception.class, (e, ctx) -> {
            log.error("Unhandled error on {} {}", ctx.method(), ctx.path(), e);
            ctx.status(500).json(new ErrorResponse("INTERNAL_ERROR", "Unexpected error"));
        });
        return app;
    }

    private void score(Context ctx) {
        TransactionEvent event;
        try {
            event = mapper.readValue(ctx.body(), TransactionEvent.class);
        } catch (JsonProcessingException e) {
            throw new ValidationException("Body is not a valid transaction event: " + e.getOriginalMessage());
        }
        if (event == null) {
            throw new ValidationException("Body is required");
        }
        ctx.json(decisions.decide(event));
    }

    private void recentDecisions(Context ctx) {
        ctx.json(store.recent(limit(ctx)));
    }

    private void getDecision(Context ctx) {
        String id = ctx.pathParam("transactionId");
        store.find(id).ifPresentOrElse(ctx::json, () ->
                ctx.status(404).json(new ErrorResponse("NOT_FOUND", "No decision for transaction " + id)));
    }

    private void listRules(Context ctx) {
        var set = rules.current();
        ctx.json(new RulesView(set.version(), set.rules().stream().map(RuleView::from).toList()));
    }

    /** Promote a shadow rule to ENFORCE (or roll it back) at runtime, without a deploy. */
    private void setRuleMode(Context ctx) {
        if (!authorised(ctx)) {
            return;
        }
        ModeRequest request;
        try {
            request = mapper.readValue(ctx.body(), ModeRequest.class);
        } catch (JsonProcessingException e) {
            throw new ValidationException("mode must be one of ENFORCE, SHADOW, DISABLED");
        }
        if (request == null || request.mode() == null) {
            throw new ValidationException("mode is required");
        }
        ctx.json(RuleView.from(rules.setMode(ctx.pathParam("id"), request.mode())));
    }

    private void demoAttack(Context ctx) {
        if (!authorised(ctx)) {
            return;
        }
        if (demo == null) {
            ctx.status(404).json(new ErrorResponse("NOT_FOUND", "Demo traffic is off (set DEMO_MODE=true)"));
            return;
        }
        Scenario scenario;
        try {
            scenario = Scenario.valueOf(ctx.pathParam("scenario").toUpperCase().replace('-', '_'));
        } catch (IllegalArgumentException e) {
            throw new ValidationException("scenario must be one of card-testing, account-takeover, mule-fan-out, amount-spike");
        }
        if (scenario == Scenario.NORMAL) {
            throw new ValidationException("NORMAL is not an attack");
        }
        ctx.status(202).json(Map.of("scenario", scenario, "accountId", demo.attack(scenario)));
    }

    private boolean authorised(Context ctx) {
        if (adminToken == null) {
            return true;
        }
        String given = ctx.header("X-Admin-Token");
        boolean ok = given != null && MessageDigest.isEqual(
                given.getBytes(StandardCharsets.UTF_8), adminToken.getBytes(StandardCharsets.UTF_8));
        if (!ok) {
            ctx.status(401).json(new ErrorResponse("UNAUTHORISED", "X-Admin-Token header missing or wrong"));
        }
        return ok;
    }

    private static int limit(Context ctx) {
        String raw = ctx.queryParam("limit");
        try {
            int limit = raw == null ? 50 : Integer.parseInt(raw);
            if (limit < 1 || limit > MAX_PAGE) {
                throw new ValidationException("limit must be between 1 and " + MAX_PAGE);
            }
            return limit;
        } catch (NumberFormatException e) {
            throw new ValidationException("limit must be an integer");
        }
    }
}
