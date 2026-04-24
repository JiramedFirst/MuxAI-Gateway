package com.muxai.gateway.api.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.muxai.gateway.provider.model.ChatMessage;
import com.muxai.gateway.provider.model.ChatRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiChatRequestTest {

    @Test
    void toInternalPropagatesNewFields() {
        Object responseFormat = Map.of("type", "json_object");
        Object streamOptions = Map.of("include_usage", true);
        OpenAiChatRequest dto = new OpenAiChatRequest(
                "gpt-4o",
                List.of(new ChatMessage("user", "hi")),
                0.0, null, 50, null, null, null, null,
                responseFormat, 42, streamOptions);

        ChatRequest internal = dto.toInternal();

        assertThat(internal.responseFormat()).isEqualTo(responseFormat);
        assertThat(internal.seed()).isEqualTo(42);
        assertThat(internal.streamOptions()).isEqualTo(streamOptions);
    }

    @Test
    void deserializesSnakeCaseFromWire() throws Exception {
        String json = """
            {
              "model": "gpt-4o",
              "messages": [{"role":"user","content":"hi"}],
              "response_format": {"type":"json_object"},
              "seed": 42,
              "stream_options": {"include_usage": true}
            }
            """;
        OpenAiChatRequest dto = new ObjectMapper().readValue(json, OpenAiChatRequest.class);

        assertThat(dto.responseFormat()).isInstanceOf(Map.class);
        assertThat(dto.seed()).isEqualTo(42);
        assertThat(dto.streamOptions()).isInstanceOf(Map.class);
    }
}
