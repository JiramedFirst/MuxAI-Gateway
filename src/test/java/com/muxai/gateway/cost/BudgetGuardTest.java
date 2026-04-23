package com.muxai.gateway.cost;

import com.muxai.gateway.auth.AppPrincipal;
import com.muxai.gateway.config.GatewayProperties;
import com.muxai.gateway.config.GatewayProperties.ApiKey;
import com.muxai.gateway.config.ProviderProperties;
import com.muxai.gateway.hotreload.ConfigRuntime;
import com.muxai.gateway.observability.RequestMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BudgetGuardTest {

    private static GatewayProperties propsWithCap(String appId, Double capUsd) {
        return new GatewayProperties(
                List.of(new ProviderProperties(
                        "p1", "openai", "https://example.com/v1", "sk", null,
                        List.of("gpt-4o"), null)),
                List.of(),
                List.of(new ApiKey("mgw_test", appId, 1000, null, null, null, null, capUsd)));
    }

    private static ConfigRuntime runtime(GatewayProperties props) {
        return new ConfigRuntime(props, new RequestMetrics(new SimpleMeterRegistry()));
    }

    private static AppPrincipal principal(GatewayProperties props) {
        ApiKey k = props.apiKeysOrEmpty().get(0);
        return new AppPrincipal(k.appId(), k);
    }

    @Test
    void noOpWhenDisabled() {
        BudgetProperties disabled = new BudgetProperties(false);
        var props = propsWithCap("app", 10.0);
        BudgetGuard guard = new BudgetGuard(disabled, runtime(props));
        guard.record("app", 999.0);
        assertThatCode(() -> guard.check(principal(props))).doesNotThrowAnyException();
    }

    @Test
    void noOpWhenAppHasNoCap() {
        BudgetProperties enabled = new BudgetProperties(true);
        var props = propsWithCap("app", null);
        BudgetGuard guard = new BudgetGuard(enabled, runtime(props));
        guard.record("app", 50.0);
        assertThatCode(() -> guard.check(principal(props))).doesNotThrowAnyException();
    }

    @Test
    void allowsRequestsWhileUnderCap() {
        BudgetProperties enabled = new BudgetProperties(true);
        var props = propsWithCap("app", 10.0);
        BudgetGuard guard = new BudgetGuard(enabled, runtime(props));
        guard.record("app", 5.0);
        assertThatCode(() -> guard.check(principal(props))).doesNotThrowAnyException();
    }

    @Test
    void blocksWhenCapReached() {
        BudgetProperties enabled = new BudgetProperties(true);
        var props = propsWithCap("app", 10.0);
        BudgetGuard guard = new BudgetGuard(enabled, runtime(props));
        guard.record("app", 10.01);
        assertThatThrownBy(() -> guard.check(principal(props)))
                .isInstanceOf(BudgetExceededException.class)
                .hasMessageContaining("app");
    }

    @Test
    void dateRolloverResetsDailyCounter() {
        BudgetProperties enabled = new BudgetProperties(true);
        var props = propsWithCap("app", 10.0);
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T12:00:00Z"));
        BudgetGuard guard = new BudgetGuard(enabled, runtime(props), clock);

        guard.record("app", 10.0);
        assertThatThrownBy(() -> guard.check(principal(props)))
                .isInstanceOf(BudgetExceededException.class);

        // Next UTC day — fresh counter, request passes again.
        clock.setNow(Instant.parse("2026-01-02T00:05:00Z"));
        assertThatCode(() -> guard.check(principal(props))).doesNotThrowAnyException();
        assertThat(guard.currentSpend("app")).isEqualTo(0.0);
    }

    @Test
    void duplicateAppIdTakesMaxCap() {
        BudgetProperties enabled = new BudgetProperties(true);
        GatewayProperties props = new GatewayProperties(
                List.of(new ProviderProperties(
                        "p1", "openai", "https://example.com/v1", "sk", null,
                        List.of("gpt-4o"), null)),
                List.of(),
                List.of(
                        new ApiKey("k1", "app", 1000, null, null, null, null, 3.0),
                        new ApiKey("k2", "app", 1000, null, null, null, null, 20.0)));
        BudgetGuard guard = new BudgetGuard(enabled, runtime(props));

        guard.record("app", 10.0);
        // Cap should be max(3, 20) = 20, so this request still passes.
        AppPrincipal p = new AppPrincipal("app", props.apiKeysOrEmpty().get(0));
        assertThatCode(() -> guard.check(p)).doesNotThrowAnyException();
    }

    private static final class MutableClock extends Clock {
        private volatile Instant now;

        MutableClock(Instant start) { this.now = start; }
        void setNow(Instant t) { this.now = t; }

        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
