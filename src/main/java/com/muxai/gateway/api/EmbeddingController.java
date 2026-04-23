package com.muxai.gateway.api;

import com.muxai.gateway.api.dto.OpenAiEmbeddingRequest;
import com.muxai.gateway.auth.AppPrincipal;
import com.muxai.gateway.auth.ModelScopeGuard;
import com.muxai.gateway.cost.BudgetGuard;
import com.muxai.gateway.observability.RequestContext;
import com.muxai.gateway.observability.RequestMetrics;
import com.muxai.gateway.provider.ProviderException;
import com.muxai.gateway.provider.model.EmbeddingResponse;
import com.muxai.gateway.router.Router;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Objects;

@RestController
@RequestMapping("/v1")
public class EmbeddingController {

    private static final String ENDPOINT = "embeddings";

    private final Router router;
    private final RequestMetrics metrics;
    private final ModelScopeGuard modelScopeGuard;
    private final BudgetGuard budgetGuard;

    public EmbeddingController(Router router, RequestMetrics metrics,
                               ModelScopeGuard modelScopeGuard, BudgetGuard budgetGuard) {
        this.router = router;
        this.metrics = metrics;
        this.modelScopeGuard = modelScopeGuard;
        this.budgetGuard = budgetGuard;
    }

    @PostMapping("/embeddings")
    public ResponseEntity<EmbeddingResponse> embed(@Valid @RequestBody OpenAiEmbeddingRequest body,
                                                   @AuthenticationPrincipal AppPrincipal principal,
                                                   HttpServletRequest http) {
        modelScopeGuard.check(principal, body.model());
        budgetGuard.check(principal);
        String requestId = RequestContext.requestId(http);
        String appId = principal != null ? principal.appId() : "unknown";

        long start = System.nanoTime();
        try {
            Router.RoutedResult<EmbeddingResponse> result = Objects.requireNonNull(
                    router.routeEmbed(body.toInternal(), appId)
                            .block(Duration.ofSeconds(120)),
                    "routeEmbed returned empty Mono");
            long latencyMs = (System.nanoTime() - start) / 1_000_000L;
            metrics.recordSuccess(requestId, appId, ENDPOINT, body.model(), result, latencyMs, null);
            return ResponseEntity.ok(result.response());
        } catch (ProviderException pe) {
            long latencyMs = (System.nanoTime() - start) / 1_000_000L;
            metrics.recordFailure(requestId, appId, ENDPOINT, body.model(), latencyMs, pe);
            throw pe;
        }
    }
}
