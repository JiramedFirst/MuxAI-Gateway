package com.muxai.gateway.auth;

import com.muxai.gateway.config.GatewayProperties;

import java.util.List;

/**
 * Identity for an authenticated request. Carries both the appId (for
 * logging/metrics/rate-limiting) and the resolved ApiKey (so downstream
 * code can read scope/role/budget without re-querying ConfigRuntime).
 *
 * apiKey is nullable for compatibility with code paths that synthesise a
 * principal outside the auth filter (mostly tests).
 */
public record AppPrincipal(String appId, GatewayProperties.ApiKey apiKey) {

    public AppPrincipal(String appId) {
        this(appId, null);
    }

    public List<String> allowedModelsOrEmpty() {
        return apiKey != null ? apiKey.allowedModelsOrEmpty() : List.of();
    }

    public boolean isAdmin() {
        return apiKey != null && apiKey.isAdmin();
    }
}
