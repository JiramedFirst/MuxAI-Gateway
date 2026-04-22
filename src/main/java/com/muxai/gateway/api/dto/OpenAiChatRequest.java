package com.muxai.gateway.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.muxai.gateway.provider.model.ChatMessage;
import com.muxai.gateway.provider.model.ChatRequest;
import com.muxai.gateway.provider.model.Tool;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record OpenAiChatRequest(
        @NotBlank String model,
        @NotEmpty @Valid List<@NotNull ChatMessage> messages,
        Double temperature,
        @JsonProperty("top_p") Double topP,
        @JsonProperty("max_tokens") Integer maxTokens,
        List<String> stop,
        Boolean stream,
        List<Tool> tools,
        @JsonProperty("tool_choice") Object toolChoice
) {
    public ChatRequest toInternal() {
        return new ChatRequest(model, messages, temperature, topP, maxTokens, stop,
                stream, tools, toolChoice);
    }
}
