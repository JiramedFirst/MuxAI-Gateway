package com.muxai.gateway.cost;

import com.muxai.gateway.config.GatewayProperties;
import com.muxai.gateway.config.ProviderProperties;
import com.muxai.gateway.config.ProviderProperties.ModelPricing;
import com.muxai.gateway.hotreload.ConfigRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Resolves USD cost per (provider, model, tokens) tuple from
 * {@link ProviderProperties#pricing()} entries declared in providers.yml.
 *
 * Rebuilds on every config reload via the standard {@link ConfigRuntime}
 * listener pattern so a hot-reloaded pricing change takes effect on the next
 * request.
 *
 * Missing pricing — model has no entry, or entries map to null — returns
 * {@code 0.0} and emits a single warning per (provider, model) pair so the
 * log doesn't drown in repeats. Sprint 2 keeps this lenient deliberately;
 * operators ramp pricing in over time and shouldn't see 5xx for a
 * cost-attribution gap.
 */
@Component
public class PricingTable {

    private static final Logger log = LoggerFactory.getLogger(PricingTable.class);

    private volatile Map<String, ModelPricing> byKey;
    private final java.util.Set<String> warned = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public PricingTable(ConfigRuntime runtime) {
        rebuild(runtime.current());
        runtime.addListener(this::rebuild);
    }

    private void rebuild(GatewayProperties props) {
        Map<String, ModelPricing> next = new HashMap<>();
        for (ProviderProperties pp : props.providersOrEmpty()) {
            for (Map.Entry<String, ModelPricing> e : pp.pricingOrEmpty().entrySet()) {
                if (e.getValue() == null) continue;
                next.put(key(pp.id(), e.getKey()), e.getValue());
            }
        }
        this.byKey = Map.copyOf(next);
        warned.clear();
        log.info("PricingTable loaded {} (provider, model) entries", byKey.size());
    }

    /**
     * Returns the USD cost for the given token counts at the configured rate
     * for {@code (providerId, model)}. Returns {@code 0.0} when no pricing is
     * declared for that pair.
     */
    public double usdFor(String providerId, String model, long promptTokens, long completionTokens) {
        if (providerId == null || model == null) return 0.0;
        ModelPricing mp = byKey.get(key(providerId, model));
        if (mp == null) {
            if (warned.add(key(providerId, model))) {
                log.warn("No pricing declared for provider={} model={}; cost recorded as $0",
                        providerId, model);
            }
            return 0.0;
        }
        double inputCost = (mp.inputPer1MUsd() * Math.max(0L, promptTokens)) / 1_000_000.0;
        double outputCost = (mp.outputPer1MUsd() * Math.max(0L, completionTokens)) / 1_000_000.0;
        return inputCost + outputCost;
    }

    private static String key(String providerId, String model) {
        return providerId + "|" + model;
    }
}
