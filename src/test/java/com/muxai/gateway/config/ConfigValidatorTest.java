package com.muxai.gateway.config;

import com.muxai.gateway.config.GatewayProperties.ApiKey;
import com.muxai.gateway.config.RouteProperties.Match;
import com.muxai.gateway.config.RouteProperties.Step;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfigValidatorTest {

    private static GatewayProperties props(List<ProviderProperties> providers,
                                           List<RouteProperties> routes,
                                           List<ApiKey> keys) {
        return new GatewayProperties(providers, routes, keys);
    }

    private static ProviderProperties openai(String id) {
        return new ProviderProperties(id, "openai", "https://api.openai.com/v1",
                "sk-test", null, List.of("gpt-4o"), null);
    }

    private static RouteProperties route(String model, String providerId) {
        return new RouteProperties(new Match(null, model),
                new Step(providerId, null), List.of());
    }

    private static ApiKey key(String k, String appId) {
        return new ApiKey(k, appId, 1000, null, null, null, null, null);
    }

    @Test
    void happyPathPasses() {
        GatewayProperties p = props(
                List.of(openai("p1")),
                List.of(route(null, "p1")),
                List.of(key("mgw_k1", "app1")));
        assertThatCode(() -> new ConfigValidator(p).validate()).doesNotThrowAnyException();
    }

    @Test
    void rejectsEmptyProviders() {
        GatewayProperties p = props(List.of(), List.of(route(null, "p1")),
                List.of(key("mgw_k1", "app1")));
        assertThatThrownBy(() -> new ConfigValidator(p).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no providers configured");
    }

    @Test
    void rejectsDuplicateProviderIds() {
        GatewayProperties p = props(
                List.of(openai("dup"), openai("dup")),
                List.of(route(null, "dup")),
                List.of(key("mgw_k1", "app1")));
        assertThatThrownBy(() -> new ConfigValidator(p).validate())
                .hasMessageContaining("duplicate provider id");
    }

    @Test
    void rejectsUnknownProviderType() {
        GatewayProperties p = props(
                List.of(new ProviderProperties("p1", "bogus", "https://x",
                        "sk", null, List.of(), null)),
                List.of(route(null, "p1")),
                List.of(key("mgw_k1", "app1")));
        assertThatThrownBy(() -> new ConfigValidator(p).validate())
                .hasMessageContaining("unknown type 'bogus'");
    }

    @Test
    void rejectsMalformedBaseUrl() {
        GatewayProperties p = props(
                List.of(new ProviderProperties("p1", "openai", "not a url",
                        "sk", null, List.of(), null)),
                List.of(route(null, "p1")),
                List.of(key("mgw_k1", "app1")));
        assertThatThrownBy(() -> new ConfigValidator(p).validate())
                .hasMessageContaining("base-url");
    }

    @Test
    void rejectsRouteReferencingUnknownProvider() {
        GatewayProperties p = props(
                List.of(openai("p1")),
                List.of(route(null, "nope")),
                List.of(key("mgw_k1", "app1")));
        assertThatThrownBy(() -> new ConfigValidator(p).validate())
                .hasMessageContaining("undefined provider 'nope'");
    }

    @Test
    void rejectsUnreachableRouteAfterCatchAll() {
        GatewayProperties p = props(
                List.of(openai("p1")),
                List.of(
                        // catch-all first
                        new RouteProperties(null, new Step("p1", null), List.of()),
                        // this one can never match — would be dead code
                        route("gpt-*", "p1")),
                List.of(key("mgw_k1", "app1")));
        assertThatThrownBy(() -> new ConfigValidator(p).validate())
                .hasMessageContaining("unreachable");
    }

    @Test
    void rejectsDuplicateApiKeyValue() {
        GatewayProperties p = props(
                List.of(openai("p1")),
                List.of(route(null, "p1")),
                List.of(key("mgw_same", "app1"), key("mgw_same", "app2")));
        assertThatThrownBy(() -> new ConfigValidator(p).validate())
                .hasMessageContaining("duplicate key value");
    }

    @Test
    void rejectsApiKeyMissingAppId() {
        GatewayProperties p = props(
                List.of(openai("p1")),
                List.of(route(null, "p1")),
                List.of(new ApiKey("mgw_k1", null, 10, null, null, null, null, null)));
        assertThatThrownBy(() -> new ConfigValidator(p).validate())
                .hasMessageContaining("app-id is required");
    }

    @Test
    void rejectsNegativeRateLimit() {
        GatewayProperties p = props(
                List.of(openai("p1")),
                List.of(route(null, "p1")),
                List.of(new ApiKey("mgw_k1", "app1", -5, null, null, null, null, null)));
        assertThatThrownBy(() -> new ConfigValidator(p).validate())
                .hasMessageContaining("rate-limit-per-min");
    }

    @Test
    void rejectsUnknownRole() {
        GatewayProperties p = props(
                List.of(openai("p1")),
                List.of(route(null, "p1")),
                List.of(new ApiKey("mgw_k1", "app1", 10,
                        null, null, "superuser", null, null)));
        assertThatThrownBy(() -> new ConfigValidator(p).validate())
                .hasMessageContaining("unknown role 'superuser'");
    }

    @Test
    void rejectsNegativeDailyBudget() {
        GatewayProperties p = props(
                List.of(openai("p1")),
                List.of(route(null, "p1")),
                List.of(new ApiKey("mgw_k1", "app1", 10,
                        null, null, null, null, -1.0)));
        assertThatThrownBy(() -> new ConfigValidator(p).validate())
                .hasMessageContaining("daily-budget-usd");
    }

    @Test
    void rejectsNegativePricing() {
        ProviderProperties badPricing = new ProviderProperties(
                "p1", "openai", "https://api.openai.com/v1", "sk-test", null,
                List.of("gpt-4o"),
                java.util.Map.of("gpt-4o",
                        new ProviderProperties.ModelPricing(-1.0, 10.0)));
        GatewayProperties p = props(
                List.of(badPricing),
                List.of(route(null, "p1")),
                List.of(key("mgw_k1", "app1")));
        assertThatThrownBy(() -> new ConfigValidator(p).validate())
                .hasMessageContaining("pricing[gpt-4o]");
    }

    @Test
    void errorMessageAggregatesMultipleProblems() {
        GatewayProperties p = props(
                List.of(new ProviderProperties("p1", null, null, null, null, List.of(), null)),
                List.of(route(null, "p1")),
                List.of());
        assertThatThrownBy(() -> new ConfigValidator(p).validate())
                .satisfies(ex -> {
                    String msg = ex.getMessage();
                    assertThat(msg).contains("type is required");
                    assertThat(msg).contains("base-url is required");
                    assertThat(msg).contains("no api-keys configured");
                });
    }
}
