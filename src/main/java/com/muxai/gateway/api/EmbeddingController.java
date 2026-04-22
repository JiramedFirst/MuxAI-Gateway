package com.muxai.gateway.api;

import com.muxai.gateway.api.dto.OpenAiEmbeddingRequest;
import com.muxai.gateway.auth.AppPrincipal;
import com.muxai.gateway.observability.RequestMetrics;
import com.muxai.gateway.provider.ProviderException;
import com.muxai.gateway.provider.model.EmbeddingResponse;
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
public class EmbeddingController {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingController.class);

    private final Router router;
    private final RequestMetrics metrics;

    public EmbeddingController(Router router, RequestMetrics metrics) {
        this.router = router;
        this.metrics = metrics;
    }

    @PostMapping("/embeddings")
    public ResponseEntity<EmbeddingResponse> embed(@Valid @RequestBody OpenAiEmbeddingRequest body,
                                                   @AuthenticationPrincipal AppPrincipal principal) {
        String requestId = UUID.randomUUID().toString();
        String appId = principal != null ? principal.appId() : "unknown";

        long start = System.nanoTime();
        try {
            Router.RoutedResult<EmbeddingResponse> result = Objects.requireNonNull(
                    router.routeEmbed(body.toInternal(), appId)
                            .block(Duration.ofSeconds(120)));
            long latencyMs = (System.nanoTime() - start) / 1_000_000L;

            metrics.recordRequest(appId, result.decision().description(), "success");
            log.info("request_id={} app_id={} model_requested={} route_matched={} " +
                            "provider_attempted={} provider_succeeded={} model_actual={} " +
                            "latency_ms={} outcome=success",
                    requestId, appId, body.model(), result.decision().description(),
                    String.join(",", result.providersAttempted()),
                    result.providerSucceeded(), result.modelActual(), latencyMs);

            return ResponseEntity.ok(result.response());
        } catch (ProviderException pe) {
            metrics.recordRequest(appId, "unknown", "error");
            throw pe;
        }
    }
}
