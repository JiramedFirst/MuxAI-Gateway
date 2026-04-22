package com.muxai.gateway.ratelimit;

import com.muxai.gateway.config.GatewayProperties;
import com.muxai.gateway.hotreload.ConfigRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-{@code app-id} token-bucket rate limiter.
 *
 * <p>Each configured app gets a bucket sized to its {@code rate-limit-per-min}:
 * capacity = limit, refill = limit tokens/minute (continuous). Apps whose
 * config omits the field (or sets it to &lt;= 0) are not limited.
 *
 * <p>The limiter reacts to hot-reloaded config: limits for existing apps are
 * rescaled in place, buckets for removed apps are evicted, and newly-added
 * apps get a fresh full bucket on their first request.
 *
 * <p>A shared {@link Backend} abstraction is in place so that a future
 * Redis-backed bucket (for cross-replica quota) can swap in without changing
 * any caller. Only {@link InMemoryBackend} ships today.
 */
@Component
public class RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);

    public record Decision(boolean allowed, long remaining, long limit, long retryAfterMillis) {
        public static final Decision UNLIMITED = new Decision(true, -1L, -1L, 0L);

        /** True when this decision represents an app with no configured limit. */
        public boolean unlimited() {
            return limit < 0;
        }
    }

    interface Backend {
        Decision tryConsume(String appId, int limitPerMin);
        void rescale(String appId, int limitPerMin);
        void evict(String appId);
    }

    private volatile Map<String, Integer> limitsByApp = Map.of();
    private final Backend backend;

    @Autowired
    public RateLimiter(ConfigRuntime runtime) {
        this(runtime, new InMemoryBackend());
    }

    RateLimiter(ConfigRuntime runtime, Backend backend) {
        this.backend = backend;
        rebuild(runtime.current());
        runtime.addListener(this::rebuild);
    }

    /** Test hook: construct from raw properties with the in-memory backend. */
    public static RateLimiter inMemory(GatewayProperties props) {
        InMemoryBackend backend = new InMemoryBackend();
        RateLimiter rl = new RateLimiter(backend);
        rl.rebuild(props);
        return rl;
    }

    private RateLimiter(Backend backend) {
        this.backend = backend;
        this.limitsByApp = Map.of();
    }

    private synchronized void rebuild(GatewayProperties props) {
        Map<String, Integer> map = new HashMap<>();
        for (GatewayProperties.ApiKey k : props.apiKeysOrEmpty()) {
            if (k.appId() != null && k.rateLimitPerMin() != null && k.rateLimitPerMin() > 0) {
                // If multiple keys share an app-id, keep the max (most permissive).
                map.merge(k.appId(), k.rateLimitPerMin(), Math::max);
            }
        }
        Map<String, Integer> previous = limitsByApp;
        Map<String, Integer> fresh = Map.copyOf(map);
        for (String appId : previous.keySet()) {
            if (!fresh.containsKey(appId)) backend.evict(appId);
        }
        for (Map.Entry<String, Integer> e : fresh.entrySet()) {
            Integer prev = previous.get(e.getKey());
            if (prev != null && !prev.equals(e.getValue())) {
                backend.rescale(e.getKey(), e.getValue());
            }
        }
        this.limitsByApp = fresh;
        log.info("RateLimiter loaded {} app quota(s)", fresh.size());
    }

    public Decision tryAcquire(String appId) {
        if (appId == null) return Decision.UNLIMITED;
        Integer limit = limitsByApp.get(appId);
        if (limit == null) return Decision.UNLIMITED;
        return backend.tryConsume(appId, limit);
    }

    /** For tests / admin introspection. */
    public Integer limitFor(String appId) {
        return appId == null ? null : limitsByApp.get(appId);
    }

    // ─── In-memory token-bucket backend (default) ────────────────────────────

    static final class InMemoryBackend implements Backend {
        private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

        @Override
        public Decision tryConsume(String appId, int limitPerMin) {
            Bucket bucket = buckets.computeIfAbsent(appId, id -> new Bucket(limitPerMin));
            return bucket.tryConsume(limitPerMin);
        }

        @Override
        public void rescale(String appId, int limitPerMin) {
            buckets.compute(appId, (k, existing) -> {
                if (existing == null) return null;
                existing.rescale(limitPerMin);
                return existing;
            });
        }

        @Override
        public void evict(String appId) { buckets.remove(appId); }
    }

    private static final class Bucket {
        private double capacity;
        private double refillPerNano;
        private double tokens;
        private long lastRefillNanos;

        Bucket(int limitPerMin) {
            this.capacity = limitPerMin;
            this.refillPerNano = limitPerMin / 60_000_000_000d;
            this.tokens = capacity;
            this.lastRefillNanos = System.nanoTime();
        }

        synchronized void rescale(int newLimitPerMin) {
            double ratio = this.capacity == 0 ? 1.0 : (newLimitPerMin / this.capacity);
            this.capacity = newLimitPerMin;
            this.refillPerNano = newLimitPerMin / 60_000_000_000d;
            this.tokens = Math.min(capacity, tokens * ratio);
        }

        synchronized Decision tryConsume(int limit) {
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
