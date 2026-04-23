package com.muxai.gateway.hotreload;

import com.muxai.gateway.config.GatewayProperties;
import com.muxai.gateway.config.ProviderProperties;
import com.muxai.gateway.config.RouteProperties;
import com.muxai.gateway.observability.RequestMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigRuntimeTest {

    private SimpleMeterRegistry meterRegistry() {
        return new SimpleMeterRegistry();
    }

    private RequestMetrics metrics(SimpleMeterRegistry registry) {
        return new RequestMetrics(registry);
    }

    private GatewayProperties empty() {
        return new GatewayProperties(List.of(), List.of(), List.of());
    }

    @Test
    void replaceAtomicallySwapsCurrentValue() {
        GatewayProperties initial = empty();
        ConfigRuntime runtime = new ConfigRuntime(initial, metrics(meterRegistry()));

        assertThat(runtime.current()).isSameAs(initial);

        GatewayProperties next = new GatewayProperties(
                List.of(new ProviderProperties("p1", "openai", "https://x", "k", null, List.of(), null)),
                List.of(),
                List.of(new GatewayProperties.ApiKey("k1", "app", 10, null, null, null, null, null)));
        runtime.replace(next);

        assertThat(runtime.current()).isSameAs(next);
    }

    @Test
    void listenersFireOnReplace() {
        ConfigRuntime runtime = new ConfigRuntime(empty(), metrics(meterRegistry()));
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<GatewayProperties> seen = new AtomicReference<>();
        runtime.addListener(p -> {
            calls.incrementAndGet();
            seen.set(p);
        });

        GatewayProperties updated = new GatewayProperties(
                List.of(),
                List.of(new RouteProperties(null,
                        new RouteProperties.Step("p1", null), List.of(), null)),
                List.of());
        runtime.replace(updated);

        assertThat(calls.get()).isEqualTo(1);
        assertThat(seen.get()).isSameAs(updated);
    }

    @Test
    void listenerExceptionDoesNotPreventOtherListeners() {
        ConfigRuntime runtime = new ConfigRuntime(empty(), metrics(meterRegistry()));
        AtomicInteger reached = new AtomicInteger();
        runtime.addListener(p -> { throw new RuntimeException("boom"); });
        runtime.addListener(p -> reached.incrementAndGet());

        runtime.replace(empty());
        assertThat(reached.get()).isEqualTo(1);
    }

    @Test
    void listenerExceptionIncrementsListenerErrorCounter() {
        SimpleMeterRegistry registry = meterRegistry();
        ConfigRuntime runtime = new ConfigRuntime(empty(), metrics(registry));
        GatewayProperties next = empty();
        runtime.addListener(p -> { throw new RuntimeException("boom"); });

        runtime.replace(next);

        // The new config must still be current — listener failures never roll back.
        assertThat(runtime.current()).isSameAs(next);
        assertThat(registry.counter("muxai.config.reload.total", "outcome", "listener_error")
                .count()).isEqualTo(1.0);
    }

    @Test
    void successfulReplaceDoesNotEmitListenerErrorMetric() {
        SimpleMeterRegistry registry = meterRegistry();
        ConfigRuntime runtime = new ConfigRuntime(empty(), metrics(registry));
        AtomicInteger calls = new AtomicInteger();
        runtime.addListener(p -> calls.incrementAndGet());

        runtime.replace(empty());

        assertThat(calls.get()).isEqualTo(1);
        assertThat(registry.find("muxai.config.reload.total")
                .tag("outcome", "listener_error")
                .counter()).isNull();
    }
}
