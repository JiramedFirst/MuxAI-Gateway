package com.muxai.gateway.cost;

import com.muxai.gateway.auth.AppPrincipal;
import com.muxai.gateway.config.GatewayProperties;
import com.muxai.gateway.config.GatewayProperties.ApiKey;
import com.muxai.gateway.hotreload.ConfigRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * In-memory per-app daily USD budget. Each request increments a counter
 * indexed by {@code (appId, UTC date)}; check() rejects with
 * {@link BudgetExceededException} if the day's running total exceeds the
 * key's {@code dailyBudgetUsd}.
 *
 * Enforcement is post-hoc: cost is only known after the provider returns
 * usage. A single in-flight request can overshoot the cap; the next request
 * sees the new total and is rejected. This is documented as "hard-cap on
 * the next call" — adequate for cost containment, not for billing-grade
 * accuracy.
 *
 * Multi-replica deployments run separate counters per replica today
 * (matches the rate-limit story). Sprint 4's Redis backend is the natural
 * place to centralise this if shared budgets become a requirement.
 */
@Component
public class BudgetGuard {

    private static final Logger log = LoggerFactory.getLogger(BudgetGuard.class);

    private final BudgetProperties props;
    private final Clock clock;
    // Key: appId|YYYY-MM-DD (UTC). Value: cumulative USD so far today.
    private final Map<String, AtomicReference<Double>> spend = new ConcurrentHashMap<>();
    // Per-app cap snapshot, rebuilt from ConfigRuntime on every reload.
    private volatile Map<String, Double> capByAppId;

    public BudgetGuard(BudgetProperties props, ConfigRuntime runtime) {
        this(props, runtime, Clock.systemUTC());
    }

    // Test seam — let tests inject a fixed clock for date-rollover assertions.
    BudgetGuard(BudgetProperties props, ConfigRuntime runtime, Clock clock) {
        this.props = props;
        this.clock = clock;
        rebuild(runtime.current());
        runtime.addListener(this::rebuild);
    }

    private void rebuild(GatewayProperties props) {
        Map<String, Double> next = new HashMap<>();
        for (ApiKey k : props.apiKeysOrEmpty()) {
            if (k.appId() == null || k.dailyBudgetUsd() == null) continue;
            // If multiple keys share an appId with different caps, take the max
            // (matches RateLimiter behaviour for duplicate appIds).
            next.merge(k.appId(), k.dailyBudgetUsd(), Math::max);
        }
        this.capByAppId = Map.copyOf(next);
    }

    /**
     * Throws {@link BudgetExceededException} if the caller has already
     * exhausted today's cap. No-op when the budget feature is disabled,
     * the principal lacks an appId, or no cap is configured for the app.
     */
    public void check(AppPrincipal principal) {
        if (!props.enabledOrDefault() || principal == null || principal.appId() == null) return;
        Double cap = capByAppId.get(principal.appId());
        if (cap == null || cap <= 0.0) return;
        double spent = currentSpend(principal.appId());
        if (spent >= cap) {
            throw new BudgetExceededException(principal.appId(), spent, cap);
        }
    }

    /**
     * Add {@code usd} to today's running total for {@code appId}. Always
     * runs (regardless of {@link BudgetProperties#enabled}) so the metric
     * stays accurate even when enforcement is off — operators can dry-run
     * caps before flipping the switch.
     */
    public void record(String appId, double usd) {
        if (appId == null || usd <= 0.0) return;
        String key = key(appId);
        spend.computeIfAbsent(key, k -> new AtomicReference<>(0.0))
                .updateAndGet(v -> v + usd);
    }

    /**
     * Today's accumulated USD for {@code appId}. Returns 0 if no spend yet.
     * Exposed for tests + future admin-overview reporting.
     */
    public double currentSpend(String appId) {
        AtomicReference<Double> ref = spend.get(key(appId));
        return ref == null ? 0.0 : ref.get();
    }

    private String key(String appId) {
        return appId + "|" + LocalDate.now(clock.withZone(ZoneOffset.UTC));
    }
}
