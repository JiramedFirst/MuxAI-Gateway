package com.muxai.gateway.router;

import com.muxai.gateway.config.GatewayProperties;
import com.muxai.gateway.config.RouteProperties;
import com.muxai.gateway.hotreload.ConfigRuntime;
import com.muxai.gateway.observability.RequestMetrics;
import com.muxai.gateway.provider.LlmProvider;
import com.muxai.gateway.provider.ProviderException;
import com.muxai.gateway.provider.ProviderRegistry;
import com.muxai.gateway.provider.model.ChatMessage;
import com.muxai.gateway.provider.model.ChatRequest;
import com.muxai.gateway.provider.model.ChatResponse;
import com.muxai.gateway.provider.model.Usage;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RouterTest {

    private LlmProvider primary;
    private LlmProvider fallback;
    private RequestMetrics metrics;

    @BeforeEach
    void setUp() {
        primary = mock(LlmProvider.class);
        fallback = mock(LlmProvider.class);
        when(primary.id()).thenReturn("primary");
        when(fallback.id()).thenReturn("fallback");
        metrics = new RequestMetrics(new SimpleMeterRegistry());
    }

    private Router buildRouter(List<RouteProperties.Step> fallbackSteps) {
        RouteProperties route = new RouteProperties(
                new RouteProperties.Match(null, null),
                new RouteProperties.Step("primary", null),
                fallbackSteps, null);
        GatewayProperties props = new GatewayProperties(List.of(), List.of(route), List.of());
        RouteMatcher matcher = new RouteMatcher(new ConfigRuntime(props, metrics));
        ProviderRegistry.Lookup lookup = new ProviderRegistry.Lookup(Map.of(
                "primary", primary,
                "fallback", fallback));
        return new Router(matcher, lookup, metrics);
    }

    private ChatRequest sampleRequest() {
        return new ChatRequest(
                "gpt-4o",
                List.of(new ChatMessage("user", "hi")),
                null, null, null, null, null);
    }

    private ChatResponse sampleResponse(String providerTag) {
        return new ChatResponse(
                "chatcmpl-" + providerTag, "chat.completion", 1L, "gpt-4o",
                List.of(new ChatResponse.Choice(0, new ChatMessage("assistant", providerTag), "stop")),
                Usage.of(1, 1));
    }

    @Test
    void primarySuccessSkipsFallback() {
        Router router = buildRouter(List.of(new RouteProperties.Step("fallback", null)));
        when(primary.chat(org.mockito.ArgumentMatchers.any()))
                .thenReturn(Mono.just(sampleResponse("p")));

        StepVerifier.create(router.routeChat(sampleRequest(), "dev"))
                .assertNext(result -> {
                    assertThat(result.response().choices().get(0).message().content()).isEqualTo("p");
                    assertThat(result.providerSucceeded()).isEqualTo("primary");
                    assertThat(result.providersAttempted()).containsExactly("primary");
                })
                .verifyComplete();
    }

    @Test
    void retryableErrorTriesFallback() {
        Router router = buildRouter(List.of(new RouteProperties.Step("fallback", null)));
        when(primary.chat(org.mockito.ArgumentMatchers.any()))
                .thenReturn(Mono.error(new ProviderException(
                        ProviderException.Code.PROVIDER_ERROR, "primary", "boom")));
        when(fallback.chat(org.mockito.ArgumentMatchers.any()))
                .thenReturn(Mono.just(sampleResponse("f")));

        StepVerifier.create(router.routeChat(sampleRequest(), "dev"))
                .assertNext(result -> {
                    assertThat(result.response().choices().get(0).message().content()).isEqualTo("f");
                    assertThat(result.providerSucceeded()).isEqualTo("fallback");
                    assertThat(result.providersAttempted()).containsExactly("primary", "fallback");
                })
                .verifyComplete();

        verify(fallback).chat(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void nonRetryableErrorDoesNotFallback() {
        Router router = buildRouter(List.of(new RouteProperties.Step("fallback", null)));
        when(primary.chat(org.mockito.ArgumentMatchers.any()))
                .thenReturn(Mono.error(new ProviderException(
                        ProviderException.Code.INVALID_REQUEST, "primary", "nope")));

        StepVerifier.create(router.routeChat(sampleRequest(), "dev"))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(ProviderException.class);
                    assertThat(((ProviderException) err).code())
                            .isEqualTo(ProviderException.Code.INVALID_REQUEST);
                })
                .verify();
    }

    @Test
    void allProvidersFailBubblesLastError() {
        Router router = buildRouter(List.of(new RouteProperties.Step("fallback", null)));
        when(primary.chat(org.mockito.ArgumentMatchers.any()))
                .thenReturn(Mono.error(new ProviderException(
                        ProviderException.Code.PROVIDER_ERROR, "primary", "boom1")));
        when(fallback.chat(org.mockito.ArgumentMatchers.any()))
                .thenReturn(Mono.error(new ProviderException(
                        ProviderException.Code.PROVIDER_ERROR, "fallback", "boom2")));

        StepVerifier.create(router.routeChat(sampleRequest(), "dev"))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(ProviderException.class);
                    ProviderException pe = (ProviderException) err;
                    assertThat(pe.providerId()).isEqualTo("fallback");
                    assertThat(pe.getMessage()).contains("boom2");
                })
                .verify();
    }

    @Test
    void noRouteYieldsInvalidRequest() {
        GatewayProperties props = new GatewayProperties(List.of(), List.of(), List.of());
        RouteMatcher matcher = new RouteMatcher(new ConfigRuntime(props, metrics));
        ProviderRegistry.Lookup lookup = new ProviderRegistry.Lookup(Map.of());
        Router router = new Router(matcher, lookup, metrics);

        StepVerifier.create(router.routeChat(sampleRequest(), "dev"))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(ProviderException.class);
                    assertThat(((ProviderException) err).code())
                            .isEqualTo(ProviderException.Code.INVALID_REQUEST);
                })
                .verify();
    }

    @Test
    void routeOverridesModelName() {
        RouteProperties route = new RouteProperties(
                new RouteProperties.Match(null, "smart"),
                new RouteProperties.Step("primary", "gpt-4o"),
                List.of(), null);
        GatewayProperties props = new GatewayProperties(List.of(), List.of(route), List.of());
        Router router = new Router(
                new RouteMatcher(new ConfigRuntime(props, metrics)),
                new ProviderRegistry.Lookup(Map.of("primary", primary)),
                metrics);

        when(primary.chat(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> {
                    ChatRequest r = inv.getArgument(0);
                    assertThat(r.model()).isEqualTo("gpt-4o");
                    return Mono.just(sampleResponse("p"));
                });

        ChatRequest req = new ChatRequest("smart",
                List.of(new ChatMessage("user", "hi")),
                null, null, null, null, null);

        StepVerifier.create(router.routeChat(req, "dev"))
                .assertNext(result -> assertThat(result.modelActual()).isEqualTo("gpt-4o"))
                .verifyComplete();
    }
}
