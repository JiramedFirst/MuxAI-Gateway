package com.muxai.gateway.api.dto;

import com.muxai.gateway.provider.model.ChatResponse;

/**
 * Wire format marker. Our internal ChatResponse already uses OpenAI field names
 * (via @JsonProperty on the record), so this class simply wraps it for API docs
 * and contract clarity.
 */
public final class OpenAiChatResponse {
    private OpenAiChatResponse() {}
    public static ChatResponse from(ChatResponse internal) { return internal; }
}
