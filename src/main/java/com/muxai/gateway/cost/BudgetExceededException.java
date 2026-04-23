package com.muxai.gateway.cost;

/**
 * Thrown when an authenticated app has exhausted its configured
 * {@code dailyBudgetUsd}. Mapped to HTTP 429 by GlobalExceptionHandler with
 * the OpenAI-compatible error shape {@code {error:{type:"budget_exceeded"}}}.
 *
 * Enforcement is post-hoc: the cap may overshoot by up to one in-flight
 * request because token usage is only knowable after the provider returns.
 * The semantic is "hard-cap on the next request" not "every request rejected".
 */
public class BudgetExceededException extends RuntimeException {

    private final String appId;
    private final double spentUsd;
    private final double capUsd;

    public BudgetExceededException(String appId, double spentUsd, double capUsd) {
        super("Daily budget exhausted for app '" + appId + "' — spent $"
                + String.format("%.4f", spentUsd) + " of $" + String.format("%.2f", capUsd)
                + " cap. Try again tomorrow (UTC) or contact ops to raise the cap.");
        this.appId = appId;
        this.spentUsd = spentUsd;
        this.capUsd = capUsd;
    }

    public String appId() { return appId; }
    public double spentUsd() { return spentUsd; }
    public double capUsd() { return capUsd; }
}
