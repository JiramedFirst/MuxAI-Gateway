package com.muxai.gateway.provider.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ChatRequestTest {

    @Test
    void newFieldsRoundTripThroughWithHelpers() {
        Object responseFormat = Map.of("type", "json_object");
        Object streamOptions = Map.of("include_usage", true);
        ChatRequest r = new ChatRequest(
                "gpt-4o",
                List.of(new ChatMessage("user", "hi")),
                0.0, null, 50, null, null, null, null,
                responseFormat, 42, streamOptions);

        assertThat(r.responseFormat()).isEqualTo(responseFormat);
        assertThat(r.seed()).isEqualTo(42);
        assertThat(r.streamOptions()).isEqualTo(streamOptions);

        ChatRequest renamed = r.withModel("gpt-4o-mini");
        assertThat(renamed.responseFormat()).isEqualTo(responseFormat);
        assertThat(renamed.seed()).isEqualTo(42);
        assertThat(renamed.streamOptions()).isEqualTo(streamOptions);

        ChatRequest streaming = r.withStream(Boolean.TRUE);
        assertThat(streaming.stream()).isTrue();
        assertThat(streaming.responseFormat()).isEqualTo(responseFormat);

        ChatRequest rewired = r.withMessages(List.of(new ChatMessage("user", "hello")));
        assertThat(rewired.messages().get(0).content()).isEqualTo("hello");
        assertThat(rewired.seed()).isEqualTo(42);
    }

    @Test
    void nineArgConvenienceConstructorNullsNewFields() {
        ChatRequest r = new ChatRequest(
                "gpt-4o",
                List.of(new ChatMessage("user", "hi")),
                0.0, null, 50, null, null, null, null);

        assertThat(r.responseFormat()).isNull();
        assertThat(r.seed()).isNull();
        assertThat(r.streamOptions()).isNull();
    }
}
