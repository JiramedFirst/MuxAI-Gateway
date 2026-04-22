package com.muxai.gateway.api;

import com.muxai.gateway.api.dto.ErrorResponse;
import com.muxai.gateway.api.dto.OpenAiChatRequest;
import com.muxai.gateway.auth.AppPrincipal;
import com.muxai.gateway.observability.RequestMetrics;
import com.muxai.gateway.provider.ProviderException;
import com.muxai.gateway.provider.model.ChatResponse;
import com.muxai.gateway.router.Router;
import jakarta.validation.Valid;
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

    private static final String ENDPOINT = "chat";

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
            long latencyMs = (System.nanoTime() - start) / 1_000_000L;
            metrics.recordSuccess(requestId, appId, ENDPOINT, body.model(), result,
                    latencyMs, result.response().usage());
            return ResponseEntity.ok(result.response());
        } catch (ProviderException pe) {
            long latencyMs = (System.nanoTime() - start) / 1_000_000L;
            metrics.recordFailure(requestId, appId, ENDPOINT, body.model(), latencyMs, pe);
            throw pe;
        }
    }
}
