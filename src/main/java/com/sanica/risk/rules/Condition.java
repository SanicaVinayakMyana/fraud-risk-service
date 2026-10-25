package com.sanica.risk.rules;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A rule's predicate, parsed from JSON. Rules are data, not code: risk analysts can add a rule, ship it in
 * shadow mode and promote it without a deploy.
 */
public sealed interface Condition {

    boolean test(RuleContext ctx);

    /** Fields this condition reads, in order of appearance; they become the evidence in the reason detail. */
    default Set<String> fields() {
        Set<String> fields = new LinkedHashSet<>();
        collectFields(fields);
        return fields;
    }

    void collectFields(Set<String> into);

    enum Op {
        GT(">"), GTE(">="), LT("<"), LTE("<="), EQ("=="), NE("!=");

        final String symbol;

        Op(String symbol) {
            this.symbol = symbol;
        }

        static Op parse(String symbol) {
            for (Op op : values()) {
                if (op.symbol.equals(symbol)) {
                    return op;
                }
            }
            throw new IllegalArgumentException("Unknown operator '" + symbol + "', expected one of > >= < <= == !=");
        }
    }

    record Compare(String field, Op op, double value) implements Condition {
        @Override
        public boolean test(RuleContext ctx) {
            double actual = ctx.number(field);
            return switch (op) {
                case GT -> actual > value;
                case GTE -> actual >= value;
                case LT -> actual < value;
                case LTE -> actual <= value;
                case EQ -> actual == value;
                case NE -> actual != value;
            };
        }

        @Override
        public void collectFields(Set<String> into) {
            into.add(field);
        }
    }

    record In(String field, Set<String> values) implements Condition {
        @Override
        public boolean test(RuleContext ctx) {
            String actual = ctx.text(field);
            return actual != null && values.contains(actual);
        }

        @Override
        public void collectFields(Set<String> into) {
            into.add(field);
        }
    }

    record All(List<Condition> conditions) implements Condition {
        @Override
        public boolean test(RuleContext ctx) {
            return conditions.stream().allMatch(c -> c.test(ctx));
        }

        @Override
        public void collectFields(Set<String> into) {
            conditions.forEach(c -> c.collectFields(into));
        }
    }

    record Any(List<Condition> conditions) implements Condition {
        @Override
        public boolean test(RuleContext ctx) {
            return conditions.stream().anyMatch(c -> c.test(ctx));
        }

        @Override
        public void collectFields(Set<String> into) {
            conditions.forEach(c -> c.collectFields(into));
        }
    }

    record Not(Condition condition) implements Condition {
        @Override
        public boolean test(RuleContext ctx) {
            return !condition.test(ctx);
        }

        @Override
        public void collectFields(Set<String> into) {
            condition.collectFields(into);
        }
    }
}
