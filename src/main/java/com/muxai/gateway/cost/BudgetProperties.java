package com.muxai.gateway.cost;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Top-level switch for per-app daily budget enforcement. Off by default —
 * the per-key {@code ApiKey.dailyBudgetUsd} fields are then advisory only
 * and the cost metric still records but never blocks.
 */
@ConfigurationProperties(prefix = "muxai.budget")
public record BudgetProperties(Boolean enabled) {

    public boolean enabledOrDefault() { return Boolean.TRUE.equals(enabled); }
}
