package com.muxai.gateway.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
public class RequestMetrics {

    private final MeterRegistry registry;

    public RequestMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void recordRequest(String appId, String route, String outcome) {
        Counter.builder("muxai.request.total")
                .description("Count of chat requests processed by the gateway")
                .tags(Tags.of(
                        Tag.of("app_id", safe(appId)),
                        Tag.of("route", safe(route)),
                        Tag.of("outcome", safe(outcome))))
                .register(registry)
                .increment();
    }

    public void recordProviderCall(String providerId, String outcome, long nanos) {
        Timer.builder("muxai.provider.call")
                .description("Latency of provider calls")
                .tags(Tags.of(
                        Tag.of("provider_id", safe(providerId)),
                        Tag.of("outcome", safe(outcome))))
                .register(registry)
                .record(nanos, TimeUnit.NANOSECONDS);
    }

    public void recordTokens(String providerId, String model, String direction, long count) {
        if (count <= 0) return;
        Counter.builder("muxai.tokens.total")
                .description("Tokens consumed/produced by providers")
                .tags(Tags.of(
                        Tag.of("provider_id", safe(providerId)),
                        Tag.of("model", safe(model)),
                        Tag.of("direction", safe(direction))))
                .register(registry)
                .increment(count);
    }

    private static String safe(String s) {
        return s == null ? "unknown" : s;
    }
}
