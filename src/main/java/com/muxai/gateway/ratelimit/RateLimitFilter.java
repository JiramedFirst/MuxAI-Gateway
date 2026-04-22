package com.muxai.gateway.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.muxai.gateway.api.dto.ErrorResponse;
import com.muxai.gateway.auth.AppPrincipal;
import com.muxai.gateway.auth.PublicPaths;
import com.muxai.gateway.observability.RequestMetrics;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Enforces per-app-id request quotas using {@link RateLimiter}. Runs after
 * {@code ApiKeyAuthFilter} in the Spring Security chain, so {@code SecurityContext}
 * already carries an {@link AppPrincipal} when a request reaches this filter.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private final RateLimiter limiter;
    private final ObjectMapper mapper;
    private final RequestMetrics metrics;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    public RateLimitFilter(RateLimiter limiter, ObjectMapper mapper, RequestMetrics metrics) {
        this.limiter = limiter;
        this.mapper = mapper;
        this.metrics = metrics;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        for (String p : PublicPaths.PATTERNS) {
            if (pathMatcher.match(p, path)) return true;
        }
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof AppPrincipal principal)) {
            chain.doFilter(request, response);
            return;
        }

        RateLimiter.Decision d = limiter.tryAcquire(principal.appId());

        if (d.limit() > 0) {
            response.setHeader("X-RateLimit-Limit", Long.toString(d.limit()));
            response.setHeader("X-RateLimit-Remaining", Long.toString(Math.max(0L, d.remaining())));
        }

        if (!d.allowed()) {
            long retrySeconds = Math.max(1L, (d.retryAfterMillis() + 999L) / 1000L);
            response.setHeader("Retry-After", Long.toString(retrySeconds));
            response.setStatus(429);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            byte[] payload = mapper.writeValueAsBytes(ErrorResponse.of(
                    "Rate limit exceeded: " + d.limit() + " requests/min for app '" + principal.appId() + "'",
                    "rate_limit_exceeded",
                    "RATE_LIMITED"));
            response.setContentLength(payload.length);
            response.getOutputStream().write(payload);
            response.flushBuffer();
            metrics.recordRequest(principal.appId(), "rate_limited", "error");
            return;
        }

        chain.doFilter(request, response);
    }
}
