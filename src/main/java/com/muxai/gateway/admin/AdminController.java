package com.muxai.gateway.admin;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.muxai.gateway.config.GatewayProperties;
import com.muxai.gateway.config.ProviderProperties;
import com.muxai.gateway.config.RouteProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/admin/api")
public class AdminController {

    private final GatewayProperties props;
    private final KeyRotationService rotationService;

    public AdminController(GatewayProperties props, KeyRotationService rotationService) {
        this.props = props;
        this.rotationService = rotationService;
    }

    @PostMapping("/keys/rotate")
    public ResponseEntity<KeyRotationService.RotationResult> rotate(
            @Valid @RequestBody RotationRequest body) throws IOException {
        return ResponseEntity.ok(rotationService.rotate(body.key()));
    }

    public record RotationRequest(@NotBlank String key) {}

    @GetMapping("/overview")
    public ResponseEntity<Overview> overview() {
        List<ProviderView> providers = new ArrayList<>();
        for (ProviderProperties p : props.providersOrEmpty()) {
            providers.add(new ProviderView(
                    p.id(),
                    p.type(),
                    p.baseUrl(),
                    mask(p.apiKey()),
                    p.timeoutMsOrDefault(),
                    p.modelsOrEmpty()));
        }

        List<RouteView> routes = new ArrayList<>();
        for (RouteProperties r : props.routesOrEmpty()) {
            RouteProperties.Match m = r.match() != null ? r.match() : RouteProperties.Match.empty();
            routes.add(new RouteView(
                    new MatchView(m.appId(), m.model()),
                    stepView(r.primary()),
                    r.fallbackOrEmpty().stream().map(AdminController::stepView).toList()));
        }

        List<ApiKeyView> keys = new ArrayList<>();
        for (GatewayProperties.ApiKey k : props.apiKeysOrEmpty()) {
            keys.add(new ApiKeyView(
                    mask(k.key()),
                    k.appId(),
                    k.rateLimitPerMin(),
                    k.roleOrDefault(),
                    k.allowedModelsOrEmpty(),
                    k.expiresAt(),
                    k.dailyBudgetUsd()));
        }

        return ResponseEntity.ok(new Overview(providers, routes, keys));
    }

    private static StepView stepView(RouteProperties.Step s) {
        return s == null ? null : new StepView(s.provider(), s.model());
    }

    static String mask(String secret) {
        if (secret == null || secret.isBlank()) return null;
        int n = secret.length();
        if (n <= 8) return "***";
        return secret.substring(0, 4) + "…" + secret.substring(n - 4);
    }

    public record Overview(List<ProviderView> providers,
                           List<RouteView> routes,
                           List<ApiKeyView> apiKeys) {}

    public record ProviderView(String id,
                               String type,
                               String baseUrl,
                               String apiKeyMasked,
                               long timeoutMs,
                               List<String> models) {}

    public record RouteView(MatchView match, StepView primary, List<StepView> fallback) {}

    public record MatchView(String appId, String model) {}

    public record StepView(String provider, String model) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ApiKeyView(String keyMasked,
                             String appId,
                             Integer rateLimitPerMin,
                             String role,
                             List<String> allowedModels,
                             Instant expiresAt,
                             Double dailyBudgetUsd) {}
}
