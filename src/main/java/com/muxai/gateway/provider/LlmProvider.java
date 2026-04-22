package com.muxai.gateway.provider;

import com.muxai.gateway.provider.model.ChatChunk;
import com.muxai.gateway.provider.model.ChatRequest;
import com.muxai.gateway.provider.model.ChatResponse;
import com.muxai.gateway.provider.model.EmbeddingRequest;
import com.muxai.gateway.provider.model.EmbeddingResponse;
import com.muxai.gateway.provider.model.OcrRequest;
import com.muxai.gateway.provider.model.OcrResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface LlmProvider {
    String id();
    String type();
    boolean supports(String model);
    ProviderCapabilities capabilities();

    Mono<ChatResponse> chat(ChatRequest request);

    default Flux<ChatChunk> chatStream(ChatRequest request) {
        return Flux.error(new UnsupportedOperationException(
                "Streaming not implemented for provider " + id()));
    }

    default Mono<EmbeddingResponse> embed(EmbeddingRequest request) {
        return Mono.error(new UnsupportedOperationException(
                "Embeddings not supported by provider " + id()));
    }

    default Mono<OcrResponse> ocr(OcrRequest request) {
        return Mono.error(new ProviderException(
                ProviderException.Code.INVALID_REQUEST, id(),
                "OCR not supported by provider " + id()));
    }
}
