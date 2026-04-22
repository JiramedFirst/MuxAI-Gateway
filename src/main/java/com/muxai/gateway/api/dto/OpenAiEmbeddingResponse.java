package com.muxai.gateway.api.dto;

import com.muxai.gateway.provider.model.EmbeddingResponse;

/** Wire format marker: EmbeddingResponse already matches OpenAI JSON shape. */
public final class OpenAiEmbeddingResponse {
    private OpenAiEmbeddingResponse() {}
    public static EmbeddingResponse from(EmbeddingResponse internal) { return internal; }
}
