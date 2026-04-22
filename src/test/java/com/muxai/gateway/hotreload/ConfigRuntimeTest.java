package com.muxai.gateway.hotreload;

import com.muxai.gateway.config.GatewayProperties;
import com.muxai.gateway.config.ProviderProperties;
import com.muxai.gateway.config.RouteProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigRuntimeTest {

    @Test
    void replaceAtomicallySwapsCurrentValue() {
        GatewayProperties initial = new GatewayProperties(List.of(), List.of(), List.of());
        ConfigRuntime runtime = new ConfigRuntime(initial);

        assertThat(runtime.current()).isSameAs(initial);

        GatewayProperties next = new GatewayProperties(
                List.of(new ProviderProperties("p1", "openai", "https://x", "k", null, List.of())),
                List.of(),
                List.of(new GatewayProperties.ApiKey("k1", "app", 10)));
        runtime.replace(next);

        assertThat(runtime.current()).isSameAs(next);
    }

    @Test
    void listenersFireOnReplace() {
        ConfigRuntime runtime = new ConfigRuntime(
                new GatewayProperties(List.of(), List.of(), List.of()));
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<GatewayProperties> seen = new AtomicReference<>();
        runtime.addListener(p -> {
            calls.incrementAndGet();
            seen.set(p);
        });

        GatewayProperties updated = new GatewayProperties(
                List.of(),
                List.of(new RouteProperties(null,
                        new RouteProperties.Step("p1", null), List.of())),
                List.of());
        runtime.replace(updated);

        assertThat(calls.get()).isEqualTo(1);
        assertThat(seen.get()).isSameAs(updated);
    }

    @Test
    void listenerExceptionDoesNotPreventOtherListeners() {
        ConfigRuntime runtime = new ConfigRuntime(
                new GatewayProperties(List.of(), List.of(), List.of()));
        AtomicInteger reached = new AtomicInteger();
        runtime.addListener(p -> { throw new RuntimeException("boom"); });
        runtime.addListener(p -> reached.incrementAndGet());

        runtime.replace(new GatewayProperties(List.of(), List.of(), List.of()));
        assertThat(reached.get()).isEqualTo(1);
    }
}
