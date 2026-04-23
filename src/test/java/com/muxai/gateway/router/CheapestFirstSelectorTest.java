package com.muxai.gateway.router;

import com.muxai.gateway.config.GatewayProperties;
import com.muxai.gateway.config.ProviderProperties;
import com.muxai.gateway.config.ProviderProperties.ModelPricing;
import com.muxai.gateway.config.RouteProperties;
import com.muxai.gateway.config.RouteProperties.Step;
import com.muxai.gateway.cost.PricingTable;
import com.muxai.gateway.hotreload.ConfigRuntime;
import com.muxai.gateway.observability.RequestMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CheapestFirstSelectorTest {

    private static GatewayProperties propsWithPricing(
            String aId, ModelPricing aPrice,
            String bId, ModelPricing bPrice) {
        return new GatewayProperties(
                List.of(
                        new ProviderProperties(aId, "openai", "https://a/v1", "k", null,
                                List.of("gpt-4o"),
                                aPrice == null ? null : Map.of("gpt-4o", aPrice)),
                        new ProviderProperties(bId, "openai", "https://b/v1", "k", null,
                                List.of("gpt-4o"),
                                bPrice == null ? null : Map.of("gpt-4o", bPrice))),
                List.of(),
                List.of());
    }

    private static ConfigRuntime runtime(GatewayProperties props) {
        return new ConfigRuntime(props, new RequestMetrics(new SimpleMeterRegistry()));
    }

    private static RouteDecision decision(Step primary, List<Step> fallback) {
        RouteProperties source = new RouteProperties(
                RouteProperties.Match.empty(), primary, fallback, "cheapest-first");
        return new RouteDecision(source, 0, primary, fallback, "test");
    }

    @Test
    void reordersFallbackInFrontWhenCheaper() {
        var props = propsWithPricing(
                "expensive", new ModelPricing(10.0, 30.0),
                "cheap", new ModelPricing(1.0, 3.0));
        CheapestFirstSelector sel = new CheapestFirstSelector(new PricingTable(runtime(props)));

        Step expensivePrimary = new Step("expensive", "gpt-4o");
        Step cheapFallback = new Step("cheap", "gpt-4o");
        var ordered = sel.orderChain(decision(expensivePrimary, List.of(cheapFallback)));

        assertThat(ordered).extracting(Step::provider).containsExactly("cheap", "expensive");
    }

    @Test
    void preservesOrderWhenAlreadyCheapest() {
        var props = propsWithPricing(
                "cheap", new ModelPricing(1.0, 3.0),
                "expensive", new ModelPricing(10.0, 30.0));
        CheapestFirstSelector sel = new CheapestFirstSelector(new PricingTable(runtime(props)));

        Step cheapPrimary = new Step("cheap", "gpt-4o");
        Step expensiveFallback = new Step("expensive", "gpt-4o");
        var ordered = sel.orderChain(decision(cheapPrimary, List.of(expensiveFallback)));

        assertThat(ordered).extracting(Step::provider).containsExactly("cheap", "expensive");
    }

    @Test
    void unpricedStepsSortToTheEnd() {
        var props = propsWithPricing(
                "priced", new ModelPricing(5.0, 5.0),
                "unpriced", null);
        CheapestFirstSelector sel = new CheapestFirstSelector(new PricingTable(runtime(props)));

        Step unpricedPrimary = new Step("unpriced", "gpt-4o");
        Step pricedFallback = new Step("priced", "gpt-4o");
        var ordered = sel.orderChain(decision(unpricedPrimary, List.of(pricedFallback)));

        // The priced provider sorts in front of the unpriced one even though
        // the unpriced one was declared as primary — this is the entire point
        // of cost-aware ordering.
        assertThat(ordered).extracting(Step::provider).containsExactly("priced", "unpriced");
    }
}
