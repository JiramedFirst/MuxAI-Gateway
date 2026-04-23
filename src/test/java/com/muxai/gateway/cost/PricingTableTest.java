package com.muxai.gateway.cost;

import com.muxai.gateway.config.GatewayProperties;
import com.muxai.gateway.config.ProviderProperties;
import com.muxai.gateway.config.ProviderProperties.ModelPricing;
import com.muxai.gateway.hotreload.ConfigRuntime;
import com.muxai.gateway.observability.RequestMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class PricingTableTest {

    private static GatewayProperties properties(Map<String, ModelPricing> pricing) {
        return new GatewayProperties(
                List.of(new ProviderProperties(
                        "openai-main", "openai", "https://api.openai.com/v1",
                        "sk", null, List.of("gpt-4o"), pricing)),
                List.of(),
                List.of());
    }

    private static ConfigRuntime runtime(GatewayProperties props) {
        return new ConfigRuntime(props, new RequestMetrics(new SimpleMeterRegistry()));
    }

    @Test
    void usdForCombinesInputAndOutput() {
        var props = properties(Map.of("gpt-4o", new ModelPricing(2.5, 10.0)));
        PricingTable table = new PricingTable(runtime(props));

        // 1000 prompt @ $2.5/M = $0.0025
        // 500 completion @ $10/M = $0.005
        // total = $0.0075
        double usd = table.usdFor("openai-main", "gpt-4o", 1000, 500);
        assertThat(usd).isCloseTo(0.0075, within(1e-9));
    }

    @Test
    void unknownProviderModelReturnsZero() {
        PricingTable table = new PricingTable(runtime(properties(Map.of())));
        assertThat(table.usdFor("openai-main", "gpt-4o", 1000, 500)).isEqualTo(0.0);
    }

    @Test
    void zeroTokensReturnsZero() {
        var props = properties(Map.of("gpt-4o", new ModelPricing(2.5, 10.0)));
        PricingTable table = new PricingTable(runtime(props));
        assertThat(table.usdFor("openai-main", "gpt-4o", 0, 0)).isEqualTo(0.0);
    }

    @Test
    void rebuildsOnConfigReload() {
        var initial = properties(Map.of("gpt-4o", new ModelPricing(1.0, 1.0)));
        ConfigRuntime rt = runtime(initial);
        PricingTable table = new PricingTable(rt);

        assertThat(table.usdFor("openai-main", "gpt-4o", 1_000_000, 0)).isCloseTo(1.0, within(1e-9));

        var updated = properties(Map.of("gpt-4o", new ModelPricing(5.0, 5.0)));
        rt.replace(updated);

        assertThat(table.usdFor("openai-main", "gpt-4o", 1_000_000, 0)).isCloseTo(5.0, within(1e-9));
    }

    @Test
    void nullProviderOrModelReturnsZero() {
        var props = properties(Map.of("gpt-4o", new ModelPricing(2.5, 10.0)));
        PricingTable table = new PricingTable(runtime(props));
        assertThat(table.usdFor(null, "gpt-4o", 100, 100)).isEqualTo(0.0);
        assertThat(table.usdFor("openai-main", null, 100, 100)).isEqualTo(0.0);
    }
}
