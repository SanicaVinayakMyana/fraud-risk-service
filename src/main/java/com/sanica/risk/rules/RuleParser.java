package com.sanica.risk.rules;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanica.risk.domain.Decision;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Parses and validates rule definitions. A typo in a field name or operator fails at load time, never as a
 * silently non-matching rule in production.
 *
 * <pre>{@code
 * {"id": "VELOCITY_60S", "description": "...", "reasonCode": "VELOCITY_60S", "points": 45,
 *  "action": "REVIEW", "mode": "ENFORCE",
 *  "when": {"all": [{"field": "txCount60s", "op": ">", "value": 5}, {"field": "channel", "in": ["CARD"]}]}}
 * }</pre>
 */
public final class RuleParser {

    private final ObjectMapper mapper;

    public RuleParser(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public RuleSet parseClasspath(String resource) {
        try (InputStream in = RuleParser.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalArgumentException("Rules resource " + resource + " not found");
            }
            return parse(mapper.readTree(in));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public RuleSet parseFile(Path path) {
        try {
            return parse(mapper.readTree(Files.readString(path)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public RuleSet parse(String json) {
        try {
            return parse(mapper.readTree(json));
        } catch (IOException e) {
            throw new IllegalArgumentException("Rules are not valid JSON: " + e.getMessage(), e);
        }
    }

    private RuleSet parse(JsonNode root) {
        String version = requiredText(root, "version", "rule set");
        List<Rule> rules = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (JsonNode node : root.path("rules")) {
            Rule rule = parseRule(node);
            if (!ids.add(rule.id())) {
                throw new IllegalArgumentException("Duplicate rule id " + rule.id());
            }
            rules.add(rule);
        }
        return new RuleSet(version, rules);
    }

    private Rule parseRule(JsonNode node) {
        String id = requiredText(node, "id", "rule");
        try {
            int points = node.path("points").asInt(0);
            if (points < 0 || points > 100) {
                throw new IllegalArgumentException("points must be between 0 and 100");
            }
            Decision action = node.hasNonNull("action") ? Decision.valueOf(node.get("action").asText()) : null;
            RuleMode mode = RuleMode.valueOf(node.path("mode").asText("ENFORCE"));
            return new Rule(
                    id,
                    requiredText(node, "description", id),
                    node.path("reasonCode").asText(id),
                    points,
                    action,
                    mode,
                    condition(node.path("when")));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Rule " + id + ": " + e.getMessage(), e);
        }
    }

    private Condition condition(JsonNode node) {
        if (!node.isObject()) {
            throw new IllegalArgumentException("condition must be an object");
        }
        if (node.has("all")) {
            return new Condition.All(children(node.get("all")));
        }
        if (node.has("any")) {
            return new Condition.Any(children(node.get("any")));
        }
        if (node.has("not")) {
            return new Condition.Not(condition(node.get("not")));
        }
        String field = requiredText(node, "field", "condition");
        if (node.has("in")) {
            if (!RuleContext.TEXT_FIELDS.contains(field)) {
                throw new IllegalArgumentException("'in' needs a text field " + RuleContext.TEXT_FIELDS + ", got " + field);
            }
            Set<String> values = new LinkedHashSet<>();
            node.get("in").forEach(v -> values.add(v.asText()));
            return new Condition.In(field, Set.copyOf(values));
        }
        if (!RuleContext.NUMERIC_FIELDS.contains(field)) {
            throw new IllegalArgumentException("Unknown field " + field + ", expected one of "
                    + new java.util.TreeSet<>(RuleContext.NUMERIC_FIELDS));
        }
        JsonNode value = node.get("value");
        if (value == null || !(value.isNumber() || value.isBoolean())) {
            throw new IllegalArgumentException("condition on " + field + " needs a numeric or boolean 'value'");
        }
        double number = value.isBoolean() ? (value.asBoolean() ? 1 : 0) : value.asDouble();
        return new Condition.Compare(field, Condition.Op.parse(node.path("op").asText("==")), number);
    }

    private List<Condition> children(JsonNode array) {
        if (!array.isArray() || array.isEmpty()) {
            throw new IllegalArgumentException("'all'/'any' need a non-empty array");
        }
        List<Condition> conditions = new ArrayList<>();
        for (Iterator<JsonNode> it = array.elements(); it.hasNext(); ) {
            conditions.add(condition(it.next()));
        }
        return conditions;
    }

    private static String requiredText(JsonNode node, String field, String owner) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException(owner + " needs a '" + field + "'");
        }
        return value.asText();
    }
}
