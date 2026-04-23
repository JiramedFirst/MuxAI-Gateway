package com.muxai.gateway.auth;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Enforces per-API-key model ACLs (the optional ApiKey.allowedModels list).
 * An empty allowedModels means "no scope" (all models permitted) so existing
 * keys without the field continue to work unchanged.
 *
 * Membership is exact-match on the model identifier the caller requested
 * (the same string that flows through to RouteMatcher). Glob support is a
 * deliberate non-goal for v1; if operators need wildcards they can list the
 * concrete model names.
 */
@Component
public class ModelScopeGuard {

    public void check(AppPrincipal principal, String requestedModel) {
        if (principal == null) return;
        List<String> allowed = principal.allowedModelsOrEmpty();
        if (allowed.isEmpty()) return;
        if (requestedModel == null || !allowed.contains(requestedModel)) {
            throw new ModelAccessDeniedException(
                    principal.appId(), requestedModel, allowed);
        }
    }
}
