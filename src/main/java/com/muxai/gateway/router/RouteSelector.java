package com.muxai.gateway.router;

import com.muxai.gateway.config.RouteProperties.Step;

import java.util.List;

/**
 * Strategy for ordering the primary-then-fallback chain a {@link RouteDecision}
 * yields. Default is {@link #PRIMARY_FIRST} (chain is taken as declared);
 * operators opt into {@link CheapestFirstSelector} via
 * {@code routes[].strategy: cheapest-first}.
 *
 * Selectors run AFTER route matching (which still picks a single
 * RouteDecision via first-match-wins on appId/model). They reorder the
 * primary + fallback list — they do NOT change which provider/model tuples
 * are eligible.
 */
public interface RouteSelector {

    String name();

    List<Step> orderChain(RouteDecision decision);

    /** Default selector — preserves the order declared in providers.yml. */
    RouteSelector PRIMARY_FIRST = new RouteSelector() {
        @Override public String name() { return "primary-first"; }
        @Override public List<Step> orderChain(RouteDecision d) {
            List<Step> chain = new java.util.ArrayList<>();
            chain.add(d.primary());
            chain.addAll(d.fallback());
            return chain;
        }
    };
}
