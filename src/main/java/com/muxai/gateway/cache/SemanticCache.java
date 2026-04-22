package com.muxai.gateway.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.muxai.gateway.provider.model.ChatRequest;
import com.muxai.gateway.provider.model.ChatResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

/**
 * Exact-match request cache for chat completions.
 *
 * The "semantic" name tracks the roadmap: today we hash the full normalized
 * request; the intent is for a future embedding-similarity lookup to live
 * behind the same interface, so callers don't need to change when it lands.
 *
 * Keying is intentionally strict — model, messages, and deterministic
 * sampling parameters are all folded into the hash. A single varied token
 * (different temperature, extra system instruction, new user turn) produces
 * a different key, which avoids cross-contamination between conversations.
 * Streaming, tool-enabled, and high-temperature requests bypass the cache
 * entirely — see {@link #cacheable(ChatRequest)}.
 */
@Component
public class SemanticCache {

    private static final Logger log = LoggerFactory.getLogger(SemanticCache.class);

    private final CacheProperties props;
    private final ObjectMapper mapper;
    private final Cache<String, ChatResponse> cache;

    public SemanticCache(CacheProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        this.cache = Caffeine.newBuilder()
                .maximumSize(props.maxEntriesOrDefault())
                .expireAfterWrite(Duration.ofSeconds(props.ttlSecondsOrDefault()))
                .recordStats()
                .build();
    }

    public boolean enabled() { return props.enabledOrDefault(); }

    public ChatResponse lookup(ChatRequest request) {
        if (!cacheable(request)) return null;
        String key = keyOf(request);
        if (key == null) return null;
        ChatResponse hit = cache.getIfPresent(key);
        if (hit != null && log.isDebugEnabled()) {
            log.debug("semantic_cache hit key={}", key.substring(0, 12));
        }
        return hit;
    }

    public void store(ChatRequest request, ChatResponse response) {
        if (!cacheable(request) || response == null) return;
        String key = keyOf(request);
        if (key == null) return;
        cache.put(key, response);
    }

    public long size() { return cache.estimatedSize(); }

    public void invalidateAll() { cache.invalidateAll(); }

    boolean cacheable(ChatRequest request) {
        if (!props.enabledOrDefault()) return false;
        if (request == null || request.messages() == null || request.messages().isEmpty()) return false;
        if (Boolean.TRUE.equals(request.stream())) return false;
        if (request.tools() != null && !request.tools().isEmpty()) return false;
        double temp = request.temperature() != null ? request.temperature() : 1.0;
        return temp <= props.maxCacheableTemperatureOrDefault();
    }

    private String keyOf(ChatRequest request) {
        try {
            byte[] payload = mapper.writeValueAsBytes(new KeyPayload(
                    request.model(),
                    request.messages(),
                    request.temperature(),
                    request.topP(),
                    request.maxTokens(),
                    request.stop()));
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(payload));
        } catch (Exception e) {
            log.warn("semantic_cache key hash failed: {}", e.getMessage());
            return null;
        }
    }

    /** Mirror of the request's cache-sensitive fields. Kept record-shaped so Jackson produces stable key bytes. */
    private record KeyPayload(
            String model,
            Object messages,
            Double temperature,
            Double topP,
            Integer maxTokens,
            Object stop
    ) {}

    // Package-private for tests.
    static MessageDigest newSha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    static String hex(byte[] b) {
        return HexFormat.of().formatHex(b);
    }

    @SuppressWarnings("unused")
    private static byte[] utf8(String s) { return s.getBytes(StandardCharsets.UTF_8); }
}
