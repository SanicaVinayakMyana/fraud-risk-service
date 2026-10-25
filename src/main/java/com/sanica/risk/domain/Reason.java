package com.sanica.risk.domain;

/**
 * Why a decision was made. {@code code} is stable and machine-readable (for analysts, appeals and dashboards),
 * {@code points} is how much it added to the risk score and {@code detail} is the human-readable evidence.
 */
public record Reason(String code, int points, String detail) {
}
