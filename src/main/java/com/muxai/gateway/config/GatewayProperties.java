package com.muxai.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

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

    public record ApiKey(String key, String appId, Integer rateLimitPerMin) {}
}
