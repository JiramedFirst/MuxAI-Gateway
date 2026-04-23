package com.muxai.gateway.api;

import com.muxai.gateway.api.dto.OcrApiRequest;
import com.muxai.gateway.auth.AppPrincipal;
import com.muxai.gateway.auth.ModelScopeGuard;
import com.muxai.gateway.observability.RequestContext;
import com.muxai.gateway.observability.RequestMetrics;
import com.muxai.gateway.provider.ProviderException;
import com.muxai.gateway.provider.model.OcrResponse;
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
public class OcrController {

    private static final String ENDPOINT = "ocr";

    private final Router router;
    private final RequestMetrics metrics;
    private final ModelScopeGuard modelScopeGuard;

    public OcrController(Router router, RequestMetrics metrics, ModelScopeGuard modelScopeGuard) {
        this.router = router;
        this.metrics = metrics;
        this.modelScopeGuard = modelScopeGuard;
    }

    @PostMapping("/ocr")
    public ResponseEntity<?> ocr(@Valid @RequestBody OcrApiRequest body,
                                 @AuthenticationPrincipal AppPrincipal principal,
                                 HttpServletRequest http) {
        modelScopeGuard.check(principal, body.model());
        String requestId = RequestContext.requestId(http);
        String appId = principal != null ? principal.appId() : "unknown";

        long start = System.nanoTime();
        try {
            Router.RoutedResult<OcrResponse> result = Objects.requireNonNull(
                    router.routeOcr(body.toInternal(), appId)
                            .block(Duration.ofSeconds(120)),
                    "routeOcr returned empty Mono");
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
