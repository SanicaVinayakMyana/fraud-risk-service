package com.sanica.risk.rules;

public enum RuleMode {
    /** Counts towards the live decision. */
    ENFORCE,
    /** Evaluated on every transaction and logged with the decision it would have caused, but never enforced. */
    SHADOW,
    /** Not evaluated. */
    DISABLED
}
