package com.muxai.gateway.hotreload;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * File-watch hot reload for {@code providers.yml}.
 *
 * <p>When enabled, the gateway polls {@code path} every {@code intervalMs}
 * and, on detecting a modified mtime, re-parses the YAML and atomically
 * swaps the {@link com.muxai.gateway.config.GatewayProperties} slice held
 * by {@link ConfigRuntime}. Routes, API keys, and rate-limit entries pick
 * up the new values on the next request.
 *
 * <p>Provider-structure changes (adding, removing, or retyping providers)
 * still need a restart — the registry is assembled once at bean creation
 * because hot-swapping WebClient connection pools safely is out of scope.
 */
@ConfigurationProperties(prefix = "muxai.hot-reload")
public record HotReloadProperties(
        Boolean enabled,
        String path,
        Long intervalMs
) {
    public boolean enabledOrDefault() { return Boolean.TRUE.equals(enabled); }
    public String pathOrDefault() { return path != null && !path.isBlank() ? path : "./config/providers.yml"; }
    public long intervalMsOrDefault() { return intervalMs != null && intervalMs > 0 ? intervalMs : 5_000L; }
}
