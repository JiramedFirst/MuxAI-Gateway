package com.muxai.gateway.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.muxai.gateway.provider.model.ChatResponse;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Caffeine-backed exact-match implementation of {@link SemanticCache.Backend}.
 *
 * Behaviour matches what was previously inlined in {@link SemanticCache}:
 * bounded size, write-expire TTL, and {@code recordStats()} for Micrometer
 * integration. No semantic change — the interface extraction is purely for
 * pluggability.
 */
@Component
public class ExactMatchBackend implements SemanticCache.Backend {

    private final Cache<String, ChatResponse> cache;

    public ExactMatchBackend(CacheProperties props) {
        this.cache = Caffeine.newBuilder()
                .maximumSize(props.maxEntriesOrDefault())
                .expireAfterWrite(Duration.ofSeconds(props.ttlSecondsOrDefault()))
                .recordStats()
                .build();
    }

    @Override
    public ChatResponse get(String key) {
        return cache.getIfPresent(key);
    }

    @Override
    public void put(String key, ChatResponse response) {
        cache.put(key, response);
    }

    @Override
    public void invalidateAll() {
        cache.invalidateAll();
    }

    @Override
    public long size() {
        return cache.estimatedSize();
    }
}
