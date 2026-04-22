package com.muxai.gateway.ratelimit;

import com.muxai.gateway.config.GatewayProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory token-bucket rate limiter keyed by app-id.
 *
 * Each configured app gets a bucket sized to its {@code rate-limit-per-min}:
 * capacity = limit, refill = limit tokens/minute (continuous). Apps whose
 * config omits the field (or sets it to <= 0) are not limited.
 *
 * State is per-process — adequate for a single gateway instance. For a
 * multi-replica deployment, swap in a Redis-backed implementation.
 */
@Component
public class RateLimiter {

    public record Decision(boolean allowed, long remaining, long limit, long retryAfterMillis) {
        public static final Decision UNLIMITED = new Decision(true, -1L, -1L, 0L);
    }

    private final Map<String, Integer> limitsByApp;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    public RateLimiter(GatewayProperties props) {
        Map<String, Integer> map = new HashMap<>();
        for (GatewayProperties.ApiKey k : props.apiKeysOrEmpty()) {
            if (k.appId() != null && k.rateLimitPerMin() != null && k.rateLimitPerMin() > 0) {
                // If multiple keys share an app-id, keep the max (most permissive).
                map.merge(k.appId(), k.rateLimitPerMin(), Math::max);
            }
        }
        this.limitsByApp = Map.copyOf(map);
    }

    public Decision tryAcquire(String appId) {
        if (appId == null) return Decision.UNLIMITED;
        Integer limit = limitsByApp.get(appId);
        if (limit == null) return Decision.UNLIMITED;
        Bucket bucket = buckets.computeIfAbsent(appId, id -> new Bucket(limit));
        return bucket.tryConsume();
    }

    /** For tests / admin introspection. */
    public Integer limitFor(String appId) {
        return appId == null ? null : limitsByApp.get(appId);
    }

    private static final class Bucket {
        private final double capacity;
        private final double refillPerNano;
        private double tokens;
        private long lastRefillNanos;

        Bucket(int limitPerMin) {
            this.capacity = limitPerMin;
            this.refillPerNano = limitPerMin / 60_000_000_000d;
            this.tokens = capacity;
            this.lastRefillNanos = System.nanoTime();
        }

        synchronized Decision tryConsume() {
            long now = System.nanoTime();
            long elapsed = now - lastRefillNanos;
            if (elapsed > 0) {
                tokens = Math.min(capacity, tokens + elapsed * refillPerNano);
                lastRefillNanos = now;
            }
            if (tokens >= 1.0) {
                tokens -= 1.0;
                return new Decision(true, (long) Math.floor(tokens), (long) capacity, 0L);
            }
            double deficit = 1.0 - tokens;
            long retryMillis = (long) Math.ceil(deficit / refillPerNano / 1_000_000d);
            return new Decision(false, 0L, (long) capacity, Math.max(retryMillis, 1L));
        }
    }
}
