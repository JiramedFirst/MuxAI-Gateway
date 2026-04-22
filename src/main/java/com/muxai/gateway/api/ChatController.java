package com.muxai.gateway.api;

import com.muxai.gateway.api.dto.ErrorResponse;
import com.muxai.gateway.api.dto.OpenAiChatRequest;
import com.muxai.gateway.auth.AppPrincipal;
import com.muxai.gateway.observability.RequestMetrics;
import com.muxai.gateway.provider.ProviderException;
import com.muxai.gateway.provider.model.ChatResponse;
import com.muxai.gateway.router.Router;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

@RestController
@RequestMapping("/v1")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final Router router;
    private final RequestMetrics metrics;

    public ChatController(Router router, RequestMetrics metrics) {
        this.router = router;
        this.metrics = metrics;
    }

    @PostMapping("/chat/completions")
    public ResponseEntity<?> chat(@Valid @RequestBody OpenAiChatRequest body,
                                  @AuthenticationPrincipal AppPrincipal principal) {
        String requestId = UUID.randomUUID().toString();
        String appId = principal != null ? principal.appId() : "unknown";

        if (Boolean.TRUE.equals(body.stream())) {
            metrics.recordRequest(appId, "stream_rejected", "error");
            return ResponseEntity.status(501).body(ErrorResponse.of(
                    "Streaming not supported in Phase 1",
                    "gateway_error",
                    "STREAMING_UNSUPPORTED"));
        }

        long start = System.nanoTime();
        try {
            Router.RoutedResult<ChatResponse> result = Objects.requireNonNull(
                    router.routeChat(body.toInternal(), appId)
                            .block(Duration.ofSeconds(120)));
            ChatResponse resp = result.response();
            long latencyMs = (System.nanoTime() - start) / 1_000_000L;

            int promptTokens = resp.usage() != null && resp.usage().promptTokens() != null
                    ? resp.usage().promptTokens() : 0;
            int completionTokens = resp.usage() != null && resp.usage().completionTokens() != null
                    ? resp.usage().completionTokens() : 0;

            metrics.recordRequest(appId, result.decision().description(), "success");
            if (result.providerSucceeded() != null) {
                metrics.recordTokens(result.providerSucceeded(), result.modelActual(), "prompt", promptTokens);
                metrics.recordTokens(result.providerSucceeded(), result.modelActual(), "completion", completionTokens);
            }

            log.info("request_id={} app_id={} model_requested={} route_matched={} " +
                            "provider_attempted={} provider_succeeded={} model_actual={} " +
                            "latency_ms={} prompt_tokens={} completion_tokens={} outcome=success error_code=null",
                    requestId, appId, body.model(), result.decision().description(),
                    String.join(",", result.providersAttempted()),
                    result.providerSucceeded(), result.modelActual(),
                    latencyMs, promptTokens, completionTokens);

            return ResponseEntity.ok(resp);
        } catch (ProviderException pe) {
            long latencyMs = (System.nanoTime() - start) / 1_000_000L;
            metrics.recordRequest(appId, "unknown", "error");
            log.info("request_id={} app_id={} model_requested={} route_matched=unknown " +
                            "provider_attempted= provider_succeeded=null model_actual=null " +
                            "latency_ms={} prompt_tokens=0 completion_tokens=0 outcome=error error_code={}",
                    requestId, appId, body.model(), latencyMs, pe.code());
            throw pe;
        }
    }
}
