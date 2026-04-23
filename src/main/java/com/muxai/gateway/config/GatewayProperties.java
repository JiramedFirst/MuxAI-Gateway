package com.muxai.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Instant;
import java.util.List;

@ConfigurationProperties(prefix = "muxai")
public record GatewayProperties(
        List<ProviderProperties> providers,
        List<RouteProperties> routes,
        List<ApiKey> apiKeys
) {
    public List<ProviderProperties> providersOrEmpty() {
        return providers != null ? providers : List.of();
    }
    public List<RouteProperties> routesOrEmpty() {
        return routes != null ? routes : List.of();
    }
    public List<ApiKey> apiKeysOrEmpty() {
        return apiKeys != null ? apiKeys : List.of();
    }

    public static final String ROLE_ADMIN = "admin";
    public static final String ROLE_APP = "app";

    public record ApiKey(
            String key,
            String appId,
            Integer rateLimitPerMin,
            List<String> allowedModels,
            List<String> allowedEndpoints,
            String role,
            Instant expiresAt,
            Double dailyBudgetUsd) {

        public List<String> allowedModelsOrEmpty() {
            return allowedModels != null ? allowedModels : List.of();
        }

        public List<String> allowedEndpointsOrEmpty() {
            return allowedEndpoints != null ? allowedEndpoints : List.of();
        }

        public String roleOrDefault() {
            return role != null ? role : ROLE_APP;
        }

        public boolean isAdmin() {
            return ROLE_ADMIN.equalsIgnoreCase(roleOrDefault());
        }

        public boolean isExpired(Instant now) {
            return expiresAt != null && now.isAfter(expiresAt);
        }
    }
}
