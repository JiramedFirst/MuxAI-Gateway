package com.muxai.gateway.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Backend selection for the per-app rate limiter.
 *
 * <p>{@code memory} (default) keeps token buckets in-process — adequate for
 * a single replica. {@code redis} stores the bucket state in a shared Redis
 * instance so quotas hold across horizontally-scaled replicas. The Redis
 * backend is a drop-in replacement: the same {@code rate-limit-per-min}
 * values apply, only the storage differs.
 *
 * <p>The Redis backend uses atomic Lua evaluation for token consumption,
 * so no two replicas double-spend the same window.
 */
@ConfigurationProperties(prefix = "muxai.rate-limit")
public record RateLimitProperties(
        String backend,
        Redis redis
) {
    public String backendOrDefault() {
        return backend == null || backend.isBlank() ? "memory" : backend.toLowerCase();
    }

    public Redis redisOrDefault() {
        return redis != null ? redis : new Redis(null, null, null, null);
    }

    public record Redis(String host, Integer port, String password, String keyPrefix) {
        public String hostOrDefault() { return host != null ? host : "localhost"; }
        public int portOrDefault() { return port != null ? port : 6379; }
        public String keyPrefixOrDefault() {
            return keyPrefix != null && !keyPrefix.isBlank() ? keyPrefix : "muxai:ratelimit:";
        }
    }
}
