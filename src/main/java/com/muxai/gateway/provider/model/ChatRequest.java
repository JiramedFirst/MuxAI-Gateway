package com.muxai.gateway.provider.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatRequest(
        String model,
        List<ChatMessage> messages,
        Double temperature,
        @JsonProperty("top_p") Double topP,
        @JsonProperty("max_tokens") Integer maxTokens,
        List<String> stop,
        Boolean stream,
        List<Tool> tools,
        @JsonProperty("tool_choice") Object toolChoice,
        @JsonProperty("response_format") Object responseFormat,
        Integer seed,
        @JsonProperty("stream_options") Object streamOptions
) {
    public ChatRequest(String model, List<ChatMessage> messages, Double temperature,
                       Double topP, Integer maxTokens, List<String> stop, Boolean stream) {
        this(model, messages, temperature, topP, maxTokens, stop, stream,
                null, null, null, null, null);
    }

    public ChatRequest(String model, List<ChatMessage> messages, Double temperature,
                       Double topP, Integer maxTokens, List<String> stop, Boolean stream,
                       List<Tool> tools, Object toolChoice) {
        this(model, messages, temperature, topP, maxTokens, stop, stream,
                tools, toolChoice, null, null, null);
    }

    public ChatRequest withModel(String newModel) {
        return new ChatRequest(newModel, messages, temperature, topP, maxTokens, stop,
                stream, tools, toolChoice, responseFormat, seed, streamOptions);
    }

    public ChatRequest withMessages(List<ChatMessage> newMessages) {
        return new ChatRequest(model, newMessages, temperature, topP, maxTokens, stop,
                stream, tools, toolChoice, responseFormat, seed, streamOptions);
    }

    public ChatRequest withStream(Boolean newStream) {
        return new ChatRequest(model, messages, temperature, topP, maxTokens, stop,
                newStream, tools, toolChoice, responseFormat, seed, streamOptions);
    }
}
