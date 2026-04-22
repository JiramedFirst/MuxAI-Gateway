package com.muxai.gateway.cache;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Chat-response cache configuration.
 *
 * When {@code enabled} is true, the gateway hashes each non-streaming,
 * tool-free chat request (model + messages + deterministic sampling params)
 * and serves a cached response on exact match. Requests with non-null
 * {@code tools} or temperature above {@link #maxCacheableTemperature} are
 * not cached — caching a stochastic generation would give the same answer
 * to every caller, which is rarely what developers want.
 *
 * The backing store is Caffeine (in-process, bounded). For a multi-replica
 * deployment, flip {@code backend: redis} to share the cache across nodes;
 * that isn't implemented here, so this class documents the knob for future
 * work without wiring a second backend.
 */
@ConfigurationProperties(prefix = "muxai.cache")
public record CacheProperties(
        Boolean enabled,
        Integer maxEntries,
        Long ttlSeconds,
        Double maxCacheableTemperature
) {
    public boolean enabledOrDefault() { return Boolean.TRUE.equals(enabled); }
    public int maxEntriesOrDefault() { return maxEntries != null ? maxEntries : 10_000; }
    public long ttlSecondsOrDefault() { return ttlSeconds != null ? ttlSeconds : 3600L; }
    public double maxCacheableTemperatureOrDefault() {
        return maxCacheableTemperature != null ? maxCacheableTemperature : 0.0;
    }
}
