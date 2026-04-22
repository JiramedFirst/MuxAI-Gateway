package com.muxai.gateway.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.muxai.gateway.api.dto.ErrorResponse;
import com.muxai.gateway.api.dto.OpenAiChatRequest;
import com.muxai.gateway.auth.AppPrincipal;
import com.muxai.gateway.cache.SemanticCache;
import com.muxai.gateway.observability.RequestContext;
import com.muxai.gateway.observability.RequestMetrics;
import com.muxai.gateway.pii.PiiRedactor;
import com.muxai.gateway.provider.ProviderException;
import com.muxai.gateway.provider.model.ChatRequest;
import com.muxai.gateway.provider.model.ChatResponse;
import com.muxai.gateway.router.Router;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.Objects;

@RestController
@RequestMapping("/v1")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);
    private static final String ENDPOINT = "chat";

    private final Router router;
    private final RequestMetrics metrics;
    private final ObjectMapper mapper;
    private final PiiRedactor piiRedactor;
    private final SemanticCache cache;

    public ChatController(Router router,
                          RequestMetrics metrics,
                          ObjectMapper mapper,
                          PiiRedactor piiRedactor,
                          SemanticCache cache) {
        this.router = router;
        this.metrics = metrics;
        this.mapper = mapper;
        this.piiRedactor = piiRedactor;
        this.cache = cache;
    }

    @PostMapping("/chat/completions")
    public Object chat(@Valid @RequestBody OpenAiChatRequest body,
                       @AuthenticationPrincipal AppPrincipal principal,
                       HttpServletRequest http) {
        String requestId = RequestContext.requestId(http);
        String appId = principal != null ? principal.appId() : "unknown";
        ChatRequest internal = piiRedactor.redact(body.toInternal());

        if (Boolean.TRUE.equals(body.stream())) {
            return streamResponse(internal, appId, requestId, body.model());
        }
        return blockingResponse(internal, appId, requestId, body.model());
    }

    private ResponseEntity<?> blockingResponse(ChatRequest internal, String appId,
                                               String requestId, String requestedModel) {
        ChatResponse cached = cache.lookup(internal);
        if (cached != null) {
            metrics.recordCacheHit(appId, ENDPOINT);
            log.info("request_id={} app_id={} endpoint={} model_requested={} cache=hit",
                    requestId, appId, ENDPOINT, requestedModel);
            return ResponseEntity.ok(cached);
        }

        long start = System.nanoTime();
        try {
            Router.RoutedResult<ChatResponse> result = Objects.requireNonNull(
                    router.routeChat(internal, appId)
                            .block(Duration.ofSeconds(120)),
                    "routeChat returned empty Mono");
            long latencyMs = (System.nanoTime() - start) / 1_000_000L;
            metrics.recordSuccess(requestId, appId, ENDPOINT, requestedModel, result,
                    latencyMs, result.response().usage());
            cache.store(internal, result.response());
            return ResponseEntity.ok(result.response());
        } catch (ProviderException pe) {
            long latencyMs = (System.nanoTime() - start) / 1_000_000L;
            metrics.recordFailure(requestId, appId, ENDPOINT, requestedModel, latencyMs, pe);
            throw pe;
        }
    }

    private SseEmitter streamResponse(ChatRequest internal, String appId,
                                      String requestId, String requestedModel) {
        // 180s matches server.tomcat.connection-timeout — stream longer than that
        // is almost always a stuck upstream, not legitimate slow generation.
        SseEmitter emitter = new SseEmitter(180_000L);
        long start = System.nanoTime();

        router.streamChat(internal, appId)
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        chunk -> {
                            try {
                                emitter.send(SseEmitter.event().data(mapper.writeValueAsString(chunk)));
                            } catch (Exception e) {
                                emitter.completeWithError(e);
                            }
                        },
                        err -> {
                            ProviderException pe = err instanceof ProviderException p ? p
                                    : new ProviderException(
                                            ProviderException.Code.PROVIDER_ERROR, "stream",
                                            "stream error: " + err.getMessage(), err);
                            long latencyMs = (System.nanoTime() - start) / 1_000_000L;
                            metrics.recordFailure(requestId, appId, ENDPOINT, requestedModel, latencyMs, pe);
                            try {
                                String payload = mapper.writeValueAsString(ErrorResponse.of(
                                        pe.getMessage(),
                                        pe.code().retryable ? "upstream_error" : "gateway_error",
                                        pe.code().name()));
                                emitter.send(SseEmitter.event().name("error").data(payload));
                                emitter.send(SseEmitter.event().data("[DONE]"));
                            } catch (Exception ignored) {
                                // Client probably disconnected — nothing useful to do.
                            }
                            emitter.complete();
                        },
                        () -> {
                            long latencyMs = (System.nanoTime() - start) / 1_000_000L;
                            metrics.recordStreamSuccess(requestId, appId, ENDPOINT, requestedModel, latencyMs);
                            try {
                                emitter.send(SseEmitter.event().data("[DONE]"));
                            } catch (Exception ignored) {
                                // Client already disconnected.
                            }
                            emitter.complete();
                        });

        return emitter;
    }
}
