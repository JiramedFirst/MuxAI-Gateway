package com.muxai.gateway.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.muxai.gateway.provider.model.ChatMessage;
import com.muxai.gateway.provider.model.ChatRequest;
import com.muxai.gateway.provider.model.ChatResponse;
import com.muxai.gateway.provider.model.Tool;
import com.muxai.gateway.provider.model.Usage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SemanticCacheTest {

    private static SemanticCache cache(boolean enabled) {
        CacheProperties props = new CacheProperties(enabled, 10, 60L, 0.0);
        return new SemanticCache(props, new ObjectMapper(), new ExactMatchBackend(props));
    }

    private static ChatRequest req(String userContent, Double temperature) {
        return new ChatRequest("gpt-4o",
                List.of(new ChatMessage("user", userContent)),
                temperature, null, 50, null, null);
    }

    private static ChatResponse resp(String text) {
        return new ChatResponse("id", "chat.completion", 1L, "gpt-4o",
                List.of(new ChatResponse.Choice(0, new ChatMessage("assistant", text), "stop")),
                Usage.of(1, 1));
    }

    @Test
    void disabledCacheIsANoop() {
        SemanticCache c = cache(false);
        c.store(req("hi", 0.0), resp("hello"));
        assertThat(c.lookup(req("hi", 0.0))).isNull();
    }

    @Test
    void exactMatchReturnsStoredResponse() {
        SemanticCache c = cache(true);
        ChatRequest r = req("hi", 0.0);
        c.store(r, resp("hello"));
        ChatResponse hit = c.lookup(r);
        assertThat(hit).isNotNull();
        assertThat(hit.choices().get(0).message().content()).isEqualTo("hello");
    }

    @Test
    void streamingRequestsAreNotCached() {
        SemanticCache c = cache(true);
        ChatRequest streaming = new ChatRequest("gpt-4o",
                List.of(new ChatMessage("user", "hi")),
                0.0, null, 50, null, Boolean.TRUE);
        c.store(streaming, resp("hello"));
        assertThat(c.lookup(streaming)).isNull();
    }

    @Test
    void toolRequestsAreNotCached() {
        SemanticCache c = cache(true);
        ChatRequest withTools = new ChatRequest("gpt-4o",
                List.of(new ChatMessage("user", "hi")),
                0.0, null, 50, null, null,
                List.of(new Tool("function", new Tool.Function("get_weather", "d", null))),
                null);
        c.store(withTools, resp("hello"));
        assertThat(c.lookup(withTools)).isNull();
    }

    @Test
    void highTemperatureRequestsAreNotCached() {
        SemanticCache c = cache(true);
        ChatRequest hot = req("hi", 0.7);
        c.store(hot, resp("hello"));
        assertThat(c.lookup(hot)).isNull();
    }

    @Test
    void differentPromptsHaveDifferentKeys() {
        SemanticCache c = cache(true);
        c.store(req("hi", 0.0), resp("A"));
        c.store(req("hello", 0.0), resp("B"));
        assertThat(c.lookup(req("hi", 0.0)).choices().get(0).message().content()).isEqualTo("A");
        assertThat(c.lookup(req("hello", 0.0)).choices().get(0).message().content()).isEqualTo("B");
    }

    @Test
    void invalidateAllDropsEntries() {
        SemanticCache c = cache(true);
        c.store(req("hi", 0.0), resp("hello"));
        c.invalidateAll();
        assertThat(c.lookup(req("hi", 0.0))).isNull();
    }
}
