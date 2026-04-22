package com.muxai.gateway.auth;

import java.util.List;

/**
 * Paths that bypass authentication and rate limiting. Consumed by both
 * SecurityConfig (to permit in the filter chain) and the request filters
 * (to skip themselves) — keeping the list here prevents drift.
 */
public final class PublicPaths {

    public static final List<String> PATTERNS = List.of(
            "/actuator/health",
            "/actuator/health/**",
            "/actuator/info",
            "/actuator/prometheus",
            "/actuator/metrics",
            "/actuator/metrics/**",
            "/swagger-ui.html",
            "/swagger-ui/**",
            "/v3/api-docs",
            "/v3/api-docs/**",
            "/error",
            "/admin",
            "/admin/",
            "/admin/index.html",
            "/admin/app.js",
            "/admin/styles.css",
            "/admin/favicon.ico"
    );

    private PublicPaths() {}
}
