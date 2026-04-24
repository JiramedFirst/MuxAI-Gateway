package com.muxai.gateway.cache;

import com.muxai.gateway.provider.model.ChatMessage;
import com.muxai.gateway.provider.model.ChatResponse;
import com.muxai.gateway.provider.model.Usage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExactMatchBackendTest {

    private static ExactMatchBackend backend() {
        return new ExactMatchBackend(new CacheProperties(true, 10, 60L, 0.0));
    }

    private static ChatResponse resp(String text) {
        return new ChatResponse("id", "chat.completion", 1L, "gpt-4o",
                List.of(new ChatResponse.Choice(0, new ChatMessage("assistant", text), "stop")),
                Usage.of(1, 1));
    }

    @Test
    void putThenGetReturnsStoredResponse() {
        ExactMatchBackend b = backend();
        b.put("k1", resp("hello"));
        ChatResponse hit = b.get("k1");
        assertThat(hit).isNotNull();
        assertThat(hit.choices().get(0).message().content()).isEqualTo("hello");
    }

    @Test
    void getOnMissingKeyReturnsNull() {
        assertThat(backend().get("missing")).isNull();
    }

    @Test
    void invalidateAllClearsEntries() {
        ExactMatchBackend b = backend();
        b.put("k1", resp("A"));
        b.put("k2", resp("B"));
        b.invalidateAll();
        assertThat(b.get("k1")).isNull();
        assertThat(b.get("k2")).isNull();
    }

    @Test
    void sizeReflectsInsertions() {
        ExactMatchBackend b = backend();
        assertThat(b.size()).isZero();
        b.put("k1", resp("A"));
        b.put("k2", resp("B"));
        assertThat(b.size()).isEqualTo(2);
    }
}
