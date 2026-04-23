package com.muxai.gateway.router;

import com.muxai.gateway.config.RouteProperties;
import com.muxai.gateway.observability.RequestMetrics;
import com.muxai.gateway.provider.LlmProvider;
import com.muxai.gateway.provider.ProviderException;
import com.muxai.gateway.provider.ProviderRegistry;
import com.muxai.gateway.provider.model.ChatChunk;
import com.muxai.gateway.provider.model.ChatRequest;
import com.muxai.gateway.provider.model.ChatResponse;
import com.muxai.gateway.provider.model.EmbeddingRequest;
import com.muxai.gateway.provider.model.EmbeddingResponse;
import com.muxai.gateway.provider.model.OcrRequest;
import com.muxai.gateway.provider.model.OcrResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class Router {

    private static final Logger log = LoggerFactory.getLogger(Router.class);

    private final RouteMatcher matcher;
    private final ProviderRegistry.Lookup providers;
    private final RequestMetrics metrics;
    private final Map<String, RouteSelector> selectorsByName;

    public Router(RouteMatcher matcher, ProviderRegistry.Lookup providers, RequestMetrics metrics) {
        this(matcher, providers, metrics, List.of());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public Router(RouteMatcher matcher, ProviderRegistry.Lookup providers, RequestMetrics metrics,
                  List<RouteSelector> selectors) {
        this.matcher = matcher;
        this.providers = providers;
        this.metrics = metrics;
        Map<String, RouteSelector> map = new HashMap<>();
        map.put(RouteSelector.PRIMARY_FIRST.name(), RouteSelector.PRIMARY_FIRST);
        for (RouteSelector s : selectors) map.put(s.name(), s);
        this.selectorsByName = Map.copyOf(map);
    }

    public Mono<RoutedResult<ChatResponse>> routeChat(ChatRequest request, String appId) {
        RouteDecision decision = matcher.findRoute(appId, request.model());
        if (decision == null) {
            return Mono.error(new ProviderException(
                    ProviderException.Code.INVALID_REQUEST,
                    "router",
                    "No route matches app=" + appId + " model=" + request.model()));
        }
        List<RouteProperties.Step> chain = buildChain(decision);
        List<String> attempted = new ArrayList<>();
        AtomicReference<String> succeeded = new AtomicReference<>();
        AtomicReference<String> modelActual = new AtomicReference<>();
        return attemptChat(chain, 0, request, null, attempted, succeeded, modelActual)
                .map(resp -> new RoutedResult<>(
                        resp, decision, List.copyOf(attempted),
                        succeeded.get(), modelActual.get()));
    }

    /**
     * Streams chat tokens. Only the primary provider is attempted — once bytes
     * have been written to the client, falling back to another provider would
     * produce a garbled stream. If the primary fails before the first chunk is
     * emitted, the error surfaces to the controller as a normal ProviderException.
     */
    public Flux<ChatChunk> streamChat(ChatRequest request, String appId) {
        RouteDecision decision = matcher.findRoute(appId, request.model());
        if (decision == null) {
            return Flux.error(new ProviderException(
                    ProviderException.Code.INVALID_REQUEST,
                    "router",
                    "No route matches app=" + appId + " model=" + request.model()));
        }
        RouteProperties.Step step = decision.primary();
        LlmProvider provider = providers.require(step.provider());
        String model = step.model() != null ? step.model() : request.model();
        ChatRequest adapted = request.withModel(model);

        long start = System.nanoTime();
        AtomicReference<String> outcome = new AtomicReference<>("success");
        return provider.chatStream(adapted)
                .doOnError(err -> outcome.set(
                        err instanceof ProviderException pe ? "error:" + pe.code() : "error:unknown"))
                .doFinally(sig -> metrics.recordProviderCall(
                        step.provider(), outcome.get(), System.nanoTime() - start));
    }

    public RouteDecision decide(String appId, String model) {
        return matcher.findRoute(appId, model);
    }

    public Mono<RoutedResult<EmbeddingResponse>> routeEmbed(EmbeddingRequest request, String appId) {
        RouteDecision decision = matcher.findRoute(appId, request.model());
        if (decision == null) {
            return Mono.error(new ProviderException(
                    ProviderException.Code.INVALID_REQUEST,
                    "router",
                    "No route matches app=" + appId + " model=" + request.model()));
        }
        List<RouteProperties.Step> chain = buildChain(decision);
        List<String> attempted = new ArrayList<>();
        AtomicReference<String> succeeded = new AtomicReference<>();
        AtomicReference<String> modelActual = new AtomicReference<>();
        return attemptEmbed(chain, 0, request, null, attempted, succeeded, modelActual)
                .map(resp -> new RoutedResult<>(
                        resp, decision, List.copyOf(attempted),
                        succeeded.get(), modelActual.get()));
    }

    public Mono<RoutedResult<OcrResponse>> routeOcr(OcrRequest request, String appId) {
        RouteDecision decision = matcher.findRoute(appId, request.model());
        if (decision == null) {
            return Mono.error(new ProviderException(
                    ProviderException.Code.INVALID_REQUEST,
                    "router",
                    "No route matches app=" + appId + " model=" + request.model()));
        }
        List<RouteProperties.Step> chain = buildChain(decision);
        List<String> attempted = new ArrayList<>();
        AtomicReference<String> succeeded = new AtomicReference<>();
        AtomicReference<String> modelActual = new AtomicReference<>();
        return attemptOcr(chain, 0, request, null, attempted, succeeded, modelActual)
                .map(resp -> new RoutedResult<>(
                        resp, decision, List.copyOf(attempted),
                        succeeded.get(), modelActual.get()));
    }

    private List<RouteProperties.Step> buildChain(RouteDecision decision) {
        String strategyName = decision.source() != null
                ? decision.source().strategyOrDefault()
                : "primary-first";
        RouteSelector selector = selectorsByName.get(strategyName);
        if (selector == null) {
            log.warn("Unknown route strategy '{}' on {} — falling back to primary-first",
                    strategyName, decision.description());
            selector = RouteSelector.PRIMARY_FIRST;
        }
        return new ArrayList<>(selector.orderChain(decision));
    }

    private Mono<ChatResponse> attemptChat(
            List<RouteProperties.Step> chain,
            int i,
            ChatRequest request,
            Throwable lastError,
            List<String> attempted,
            AtomicReference<String> succeeded,
            AtomicReference<String> modelActual) {

        if (i >= chain.size()) {
            return Mono.error(lastError != null ? lastError :
                    new ProviderException(
                            ProviderException.Code.PROVIDER_ERROR, "router",
                            "Route chain exhausted with no error recorded"));
        }
        RouteProperties.Step step = chain.get(i);
        LlmProvider provider = providers.require(step.provider());
        String model = step.model() != null ? step.model() : request.model();
        ChatRequest adapted = request.withModel(model);
        attempted.add(step.provider());

        long start = System.nanoTime();
        return provider.chat(adapted)
                .doOnSuccess(resp -> {
                    succeeded.set(step.provider());
                    modelActual.set(model);
                    metrics.recordProviderCall(step.provider(), "success", System.nanoTime() - start);
                })
                .onErrorResume(ProviderException.class, e -> {
                    metrics.recordProviderCall(step.provider(), "error:" + e.code(), System.nanoTime() - start);
                    if (!e.code().retryable || i + 1 >= chain.size()) {
                        return Mono.error(e);
                    }
                    log.warn("Provider {} failed ({}); trying fallback", step.provider(), e.code());
                    return attemptChat(chain, i + 1, request, e, attempted, succeeded, modelActual);
                });
    }

    private Mono<EmbeddingResponse> attemptEmbed(
            List<RouteProperties.Step> chain,
            int i,
            EmbeddingRequest request,
            Throwable lastError,
            List<String> attempted,
            AtomicReference<String> succeeded,
            AtomicReference<String> modelActual) {

        if (i >= chain.size()) {
            return Mono.error(lastError != null ? lastError :
                    new ProviderException(
                            ProviderException.Code.PROVIDER_ERROR, "router",
                            "Route chain exhausted with no error recorded"));
        }
        RouteProperties.Step step = chain.get(i);
        LlmProvider provider = providers.require(step.provider());
        String model = step.model() != null ? step.model() : request.model();
        EmbeddingRequest adapted = request.withModel(model);
        attempted.add(step.provider());

        long start = System.nanoTime();
        return provider.embed(adapted)
                .doOnSuccess(resp -> {
                    succeeded.set(step.provider());
                    modelActual.set(model);
                    metrics.recordProviderCall(step.provider(), "success", System.nanoTime() - start);
                })
                .onErrorResume(ProviderException.class, e -> {
                    metrics.recordProviderCall(step.provider(), "error:" + e.code(), System.nanoTime() - start);
                    if (!e.code().retryable || i + 1 >= chain.size()) {
                        return Mono.error(e);
                    }
                    log.warn("Provider {} failed ({}); trying fallback", step.provider(), e.code());
                    return attemptEmbed(chain, i + 1, request, e, attempted, succeeded, modelActual);
                });
    }

    private Mono<OcrResponse> attemptOcr(
            List<RouteProperties.Step> chain,
            int i,
            OcrRequest request,
            Throwable lastError,
            List<String> attempted,
            AtomicReference<String> succeeded,
            AtomicReference<String> modelActual) {

        if (i >= chain.size()) {
            return Mono.error(lastError != null ? lastError :
                    new ProviderException(
                            ProviderException.Code.PROVIDER_ERROR, "router",
                            "Route chain exhausted with no error recorded"));
        }
        RouteProperties.Step step = chain.get(i);
        LlmProvider provider = providers.require(step.provider());
        String model = step.model() != null ? step.model() : request.model();
        OcrRequest adapted = request.withModel(model);
        attempted.add(step.provider());

        long start = System.nanoTime();
        return provider.ocr(adapted)
                .doOnSuccess(resp -> {
                    succeeded.set(step.provider());
                    modelActual.set(model);
                    metrics.recordProviderCall(step.provider(), "success", System.nanoTime() - start);
                })
                .onErrorResume(ProviderException.class, e -> {
                    metrics.recordProviderCall(step.provider(), "error:" + e.code(), System.nanoTime() - start);
                    if (!e.code().retryable || i + 1 >= chain.size()) {
                        return Mono.error(e);
                    }
                    log.warn("Provider {} failed ({}); trying fallback", step.provider(), e.code());
                    return attemptOcr(chain, i + 1, request, e, attempted, succeeded, modelActual);
                });
    }

    public record RoutedResult<T>(
            T response,
            RouteDecision decision,
            List<String> providersAttempted,
            String providerSucceeded,
            String modelActual
    ) {}
}
