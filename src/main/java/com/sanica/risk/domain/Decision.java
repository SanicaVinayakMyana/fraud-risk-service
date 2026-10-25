package com.sanica.risk.domain;

/** Ordered from least to most severe, so {@link #max} picks the stricter of two outcomes. */
public enum Decision {
    ALLOW,
    REVIEW,
    BLOCK;

    public static Decision max(Decision a, Decision b) {
        return a.compareTo(b) >= 0 ? a : b;
    }
}
