package com.muxai.gateway.provider.openai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.muxai.gateway.config.ProviderProperties;
import com.muxai.gateway.provider.LlmProvider;
import com.muxai.gateway.provider.ProviderCapabilities;
import com.muxai.gateway.provider.ProviderException;
import com.muxai.gateway.provider.model.ChatChunk;
import com.muxai.gateway.provider.model.ChatRequest;
import com.muxai.gateway.provider.model.ChatResponse;
import com.muxai.gateway.provider.model.EmbeddingRequest;
import com.muxai.gateway.provider.model.EmbeddingResponse;
import com.muxai.gateway.provider.model.OcrRequest;
import com.muxai.gateway.provider.model.OcrResponse;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.ConnectException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

public class OpenAiProvider implements LlmProvider {

    private final ProviderProperties props;
    private final WebClient http;
    private final ObjectMapper mapper;

    public OpenAiProvider(ProviderProperties props, WebClient http, ObjectMapper mapper) {
        this.props = props;
        this.http = http;
        this.mapper = mapper;
    }

    @Override
    public String id() { return props.id(); }

    @Override
    public String type() { return "openai"; }

    @Override
    public boolean supports(String model) {
        return props.modelsOrEmpty().contains(model);
    }

    @Override
    public ProviderCapabilities capabilities() {
        return ProviderCapabilities.openAiFull();
    }

    @Override
    public Mono<ChatResponse> chat(ChatRequest request) {
        return guardApiKey()
                .then(http.post()
                        .uri("/chat/completions")
                        .bodyValue(request.withStream(null))
                        .retrieve()
                        .onStatus(HttpStatusCode::isError, this::mapErrorStatus)
                        .bodyToMono(ChatResponse.class))
                .timeout(Duration.ofMillis(props.timeoutMsOrDefault()))
                .onErrorMap(this::translate);
    }

    @Override
    public Flux<ChatChunk> chatStream(ChatRequest request) {
        if (props.apiKey() == null || props.apiKey().isBlank()) {
            return Flux.error(new ProviderException(
                    ProviderException.Code.AUTH_FAILED, props.id(),
                    "Provider '" + props.id() + "' has no API key configured"));
        }
        ParameterizedTypeReference<ServerSentEvent<String>> type = new ParameterizedTypeReference<>() {};
        return http.post()
                .uri("/chat/completions")
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(request.withStream(Boolean.TRUE))
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::mapErrorStatus)
                .bodyToFlux(type)
                .timeout(Duration.ofMillis(props.timeoutMsOrDefault()))
                .takeUntil(evt -> "[DONE]".equals(evt.data()))
                .filter(evt -> evt.data() != null && !"[DONE]".equals(evt.data()))
                .concatMap(evt -> {
                    try {
                        ChatChunk chunk = mapper.readValue(evt.data(), ChatChunk.class);
                        return Flux.just(chunk);
                    } catch (Exception e) {
                        return Flux.empty();
                    }
                })
                .onErrorMap(this::translate);
    }

    @Override
    public Mono<EmbeddingResponse> embed(EmbeddingRequest request) {
        return guardApiKey()
                .then(http.post()
                        .uri("/embeddings")
                        .bodyValue(request)
                        .retrieve()
                        .onStatus(HttpStatusCode::isError, this::mapErrorStatus)
                        .bodyToMono(EmbeddingResponse.class))
                .timeout(Duration.ofMillis(props.timeoutMsOrDefault()))
                .onErrorMap(this::translate);
    }

    @Override
    public Mono<OcrResponse> ocr(OcrRequest request) {
        String prompt = (request.prompt() == null || request.prompt().isBlank())
                ? "Extract all text from this image and return it as Markdown, preserving layout where possible."
                : request.prompt();
        double temperature = request.temperature() != null ? request.temperature() : 0.1;

        Map<String, Object> payload = Map.of(
                "model", request.model(),
                "temperature", temperature,
                "messages", List.of(Map.of(
                        "role", "user",
                        "content", List.of(
                                Map.of("type", "text", "text", prompt),
                                Map.of("type", "image_url",
                                        "image_url", Map.of("url", request.imageUrl()))))));

        return guardApiKey()
                .then(http.post()
                        .uri("/chat/completions")
                        .bodyValue(payload)
                        .retrieve()
                        .onStatus(HttpStatusCode::isError, this::mapErrorStatus)
                        .bodyToMono(ChatResponse.class))
                .timeout(Duration.ofMillis(props.timeoutMsOrDefault()))
                .map(resp -> {
                    String text = (resp.choices() == null || resp.choices().isEmpty()
                            || resp.choices().get(0).message() == null)
                            ? ""
                            : resp.choices().get(0).message().contentAsText();
                    return new OcrResponse(
                            resp.model() != null ? resp.model() : request.model(),
                            text,
                            resp.usage());
                })
                .onErrorMap(this::translate);
    }

    private Mono<Void> guardApiKey() {
        if (props.apiKey() == null || props.apiKey().isBlank()) {
            return Mono.error(new ProviderException(
                    ProviderException.Code.AUTH_FAILED,
                    props.id(),
                    "Provider '" + props.id() + "' has no API key configured"));
        }
        return Mono.empty();
    }

    private Mono<? extends Throwable> mapErrorStatus(org.springframework.web.reactive.function.client.ClientResponse response) {
        int status = response.statusCode().value();
        return response.bodyToMono(String.class)
                .defaultIfEmpty("")
                .map(body -> switch (status) {
                    case 401, 403 -> new ProviderException(
                            ProviderException.Code.AUTH_FAILED, props.id(),
                            "Provider auth failed: " + body);
                    case 429 -> new ProviderException(
                            ProviderException.Code.RATE_LIMITED, props.id(),
                            "Provider rate-limited: " + body);
                    case 408, 504 -> new ProviderException(
                            ProviderException.Code.TIMEOUT, props.id(),
                            "Provider timed out: " + body);
                    default -> {
                        if (status >= 500) {
                            yield new ProviderException(
                                    ProviderException.Code.PROVIDER_ERROR, props.id(),
                                    "Provider error " + status + ": " + body);
                        }
                        yield new ProviderException(
                                ProviderException.Code.INVALID_REQUEST, props.id(),
                                "Bad request to provider (" + status + "): " + body);
                    }
                });
    }

    private Throwable translate(Throwable t) {
        if (t instanceof ProviderException pe) {
            return pe;
        }
        if (t instanceof TimeoutException) {
            return new ProviderException(
                    ProviderException.Code.TIMEOUT, props.id(),
                    "Request to " + props.id() + " timed out", t);
        }
        if (t instanceof DataBufferLimitException) {
            return new ProviderException(
                    ProviderException.Code.PROVIDER_ERROR, props.id(),
                    "Response too large from " + props.id(), t);
        }
        Throwable root = rootCause(t);
        if (root instanceof ConnectException || root instanceof java.nio.channels.ClosedChannelException) {
            return new ProviderException(
                    ProviderException.Code.NETWORK_ERROR, props.id(),
                    "Network error calling " + props.id() + ": " + root.getMessage(), t);
        }
        return new ProviderException(
                ProviderException.Code.PROVIDER_ERROR, props.id(),
                "Unexpected error calling " + props.id() + ": " + t.getMessage(), t);
    }

    private static Throwable rootCause(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur;
    }
}
