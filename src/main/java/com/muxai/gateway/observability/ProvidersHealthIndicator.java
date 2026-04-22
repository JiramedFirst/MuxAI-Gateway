package com.muxai.gateway.observability;

import com.muxai.gateway.provider.LlmProvider;
import com.muxai.gateway.provider.ProviderRegistry;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Exposes the configured providers under {@code /actuator/health/providers}.
 *
 * This is a static configuration-presence check, not a liveness probe against
 * each backend: calling OpenAI / Anthropic on every health poll would burn
 * money and budget, and a transient upstream outage should not flip the
 * gateway itself to DOWN — the router already handles per-request fallback.
 *
 * Health is DOWN only when no providers are registered at all, which means
 * every request would 502. Use the detail map (provider ids + types) to
 * confirm hot-wired env vars are being picked up correctly.
 */
// Leave the default bean name (providersHealthIndicator) — the actuator strips
// the "HealthIndicator" suffix, so this shows up as "providers" inside the
// /actuator/health body. Naming the bean "providers" directly would clash
// with ProviderRegistry's Map<String, LlmProvider> bean of the same name.
@Component
public class ProvidersHealthIndicator implements HealthIndicator {

    private final ProviderRegistry.Lookup providers;

    public ProvidersHealthIndicator(ProviderRegistry.Lookup providers) {
        this.providers = providers;
    }

    @Override
    public Health health() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("count", providers.all().size());
        Map<String, String> byId = new LinkedHashMap<>();
        for (LlmProvider p : providers.all()) {
            byId.put(p.id(), p.type());
        }
        details.put("providers", byId);

        if (providers.all().isEmpty()) {
            return Health.down().withDetails(details).build();
        }
        return Health.up().withDetails(details).build();
    }
}
