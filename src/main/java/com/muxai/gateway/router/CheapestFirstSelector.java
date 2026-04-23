package com.muxai.gateway.router;

import com.muxai.gateway.config.RouteProperties.Step;
import com.muxai.gateway.cost.PricingTable;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Reorders the primary + fallback chain so the step with the lowest declared
 * input-token rate runs first. Steps that lack pricing in providers.yml sort
 * to the end (so an unpriced provider isn't accidentally treated as free).
 *
 * Sorts by {@code inputPer1MUsd} only — completion rates vary per request
 * and aren't a stable ordering signal. This is deliberately approximate:
 * the goal is "prefer cheaper providers when configured options are
 * comparable", not "billing-grade cost optimisation".
 */
@Component
public class CheapestFirstSelector implements RouteSelector {

    private final PricingTable pricingTable;

    public CheapestFirstSelector(PricingTable pricingTable) {
        this.pricingTable = pricingTable;
    }

    @Override
    public String name() { return "cheapest-first"; }

    @Override
    public List<Step> orderChain(RouteDecision decision) {
        List<Step> chain = new ArrayList<>();
        chain.add(decision.primary());
        chain.addAll(decision.fallback());
        chain.sort(Comparator.comparingDouble(this::sortKey));
        return chain;
    }

    private double sortKey(Step step) {
        if (step == null || step.provider() == null || step.model() == null) {
            return Double.MAX_VALUE;
        }
        // Use 1M tokens as the comparison unit so unconfigured (=0) sorts to the
        // very front — except we WANT unconfigured to sort to the end. So bump
        // zero results into the sentinel range.
        double cost = pricingTable.usdFor(step.provider(), step.model(), 1_000_000, 0);
        return cost <= 0.0 ? Double.MAX_VALUE : cost;
    }
}
