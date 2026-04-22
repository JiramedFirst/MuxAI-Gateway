package com.muxai.gateway.router;

import com.muxai.gateway.config.GatewayProperties;
import com.muxai.gateway.config.RouteProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RouteMatcherTest {

    private GatewayProperties props(List<RouteProperties> routes) {
        return new GatewayProperties(List.of(), routes, List.of());
    }

    private RouteProperties route(String appId, String modelPattern, String provider) {
        return new RouteProperties(
                new RouteProperties.Match(appId, modelPattern),
                new RouteProperties.Step(provider, null),
                List.of());
    }

    @Test
    void matchesExactModel() {
        RouteMatcher matcher = new RouteMatcher(props(List.of(
                route(null, "gpt-4o", "openai-main"))));
        RouteDecision d = matcher.findRoute("dev", "gpt-4o");
        assertThat(d).isNotNull();
        assertThat(d.primary().provider()).isEqualTo("openai-main");
    }

    @Test
    void matchesGlobModel() {
        RouteMatcher matcher = new RouteMatcher(props(List.of(
                route(null, "claude-*", "anthropic-main"))));
        RouteDecision d = matcher.findRoute("dev", "claude-opus-4-7");
        assertThat(d).isNotNull();
        assertThat(d.primary().provider()).isEqualTo("anthropic-main");
    }

    @Test
    void matchesAppId() {
        RouteMatcher matcher = new RouteMatcher(props(List.of(
                route("hr-app", null, "ollama-local"),
                route(null, null, "openai-main"))));
        RouteDecision hr = matcher.findRoute("hr-app", "whatever");
        RouteDecision other = matcher.findRoute("dev", "gpt-4o");
        assertThat(hr.primary().provider()).isEqualTo("ollama-local");
        assertThat(other.primary().provider()).isEqualTo("openai-main");
    }

    @Test
    void firstDeclaredWins() {
        RouteMatcher matcher = new RouteMatcher(props(List.of(
                route(null, "gpt-4o", "first"),
                route(null, "gpt-*", "second"))));
        RouteDecision d = matcher.findRoute("dev", "gpt-4o");
        assertThat(d.primary().provider()).isEqualTo("first");
    }

    @Test
    void catchAllMatchesAnything() {
        RouteMatcher matcher = new RouteMatcher(props(List.of(
                route(null, null, "openai-main"))));
        assertThat(matcher.findRoute("dev", "gpt-4o").primary().provider()).isEqualTo("openai-main");
        assertThat(matcher.findRoute("any", "anything").primary().provider()).isEqualTo("openai-main");
    }

    @Test
    void returnsNullWhenNoMatch() {
        RouteMatcher matcher = new RouteMatcher(props(List.of(
                route("hr-app", null, "ollama-local"))));
        assertThat(matcher.findRoute("other", "gpt-4o")).isNull();
    }

    @Test
    void appSpecificBeforeCatchAll() {
        RouteMatcher matcher = new RouteMatcher(props(List.of(
                route("hr-app", null, "ollama-local"),
                route(null, null, "openai-main"))));
        assertThat(matcher.findRoute("hr-app", "any").primary().provider()).isEqualTo("ollama-local");
        assertThat(matcher.findRoute("dev", "any").primary().provider()).isEqualTo("openai-main");
    }
}
