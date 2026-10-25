package com.sanica.risk.rules;

import com.sanica.risk.domain.ValidationException;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Holds the active rule set. Readers take a consistent snapshot per decision; a mode change swaps in a new
 * immutable set with a bumped version, so no decision ever mixes two versions of the rules.
 */
public final class RuleRepository {

    private static final Logger log = LoggerFactory.getLogger(RuleRepository.class);

    private final AtomicReference<RuleSet> current;

    public RuleRepository(RuleSet initial) {
        this.current = new AtomicReference<>(initial);
    }

    public RuleSet current() {
        return current.get();
    }

    /** Promotes a shadow rule to enforcement, demotes it back, or disables it. Returns the updated rule. */
    public Rule setMode(String ruleId, RuleMode mode) {
        RuleSet updated = current.updateAndGet(set -> {
            Rule rule = set.find(ruleId);
            if (rule == null) {
                throw new ValidationException("Unknown rule " + ruleId);
            }
            List<Rule> rules = set.rules().stream().map(r -> r.id().equals(ruleId) ? r.withMode(mode) : r).toList();
            return new RuleSet(nextVersion(set.version()), rules);
        });
        log.info("Rule {} is now {} (rules version {})", ruleId, mode, updated.version());
        return updated.find(ruleId);
    }

    /** "2026-10-25.1" becomes "2026-10-25.1-r1", then "2026-10-25.1-r2", ... */
    static String nextVersion(String version) {
        int marker = version.lastIndexOf("-r");
        if (marker > 0) {
            try {
                return version.substring(0, marker) + "-r" + (Integer.parseInt(version.substring(marker + 2)) + 1);
            } catch (NumberFormatException ignored) {
                // Not our suffix; append a fresh one below.
            }
        }
        return version + "-r1";
    }
}
