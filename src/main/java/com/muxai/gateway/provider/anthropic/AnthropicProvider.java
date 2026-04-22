package com.muxai.gateway.provider.anthropic;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.muxai.gateway.config.ProviderProperties;
import com.muxai.gateway.provider.LlmProvider;
import com.muxai.gateway.provider.ProviderCapabilities;
import com.muxai.gateway.provider.ProviderException;
import com.muxai.gateway.provider.model.ChatMessage;
import com.muxai.gateway.provider.model.ChatRequest;
import com.muxai.gateway.provider.model.ChatResponse;
import com.muxai.gateway.provider.model.Usage;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.net.ConnectException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;

public class AnthropicProvider implements LlmProvider {

    static final int DEFAULT_MAX_TOKENS = 4096;

    private final ProviderProperties props;
    private final WebClient http;

    public AnthropicProvider(ProviderProperties props, WebClient http) {
        this.props = props;
        this.http = http;
    }

    @Override public String id() { return props.id(); }
    @Override public String type() { return "anthropic"; }
    @Override public boolean supports(String model) { return props.modelsOrEmpty().contains(model); }
    @Override public ProviderCapabilities capabilities() { return ProviderCapabilities.chatOnly(); }

    @Override
    public Mono<ChatResponse> chat(ChatRequest request) {
        if (props.apiKey() == null || props.apiKey().isBlank()) {
            return Mono.error(new ProviderException(
                    ProviderException.Code.AUTH_FAILED, props.id(),
                    "Provider '" + props.id() + "' has no API key configured"));
        }
        AnthropicMessagesRequest body = toAnthropic(request);

        return http.post()
                .uri("/messages")
                .bodyValue(body)
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::mapErrorStatus)
                .bodyToMono(AnthropicMessagesResponse.class)
                .timeout(Duration.ofMillis(props.timeoutMsOrDefault()))
                .map(this::toOpenAi)
                .onErrorMap(this::translate);
    }

    // ─── Translation: internal → Anthropic native ────────────────────────────

    static AnthropicMessagesRequest toAnthropic(ChatRequest req) {
        String system = null;
        List<ChatMessage> conversation = new ArrayList<>();
        if (req.messages() != null) {
            for (ChatMessage m : req.messages()) {
                if ("system".equals(m.role())) {
                    String text = m.content() == null ? "" : m.content().toString();
                    system = (system == null) ? text : (system + "\n\n" + text);
                } else {
                    conversation.add(m);
                }
            }
        }
        int maxTokens = req.maxTokens() != null ? req.maxTokens() : DEFAULT_MAX_TOKENS;
        return new AnthropicMessagesRequest(
                req.model(),
                system,
                conversation,
                maxTokens,
                req.temperature(),
                req.topP(),
                req.stop()
        );
    }

    // ─── Translation: Anthropic native → internal ────────────────────────────

    ChatResponse toOpenAi(AnthropicMessagesResponse a) {
        String text = "";
        if (a.content() != null) {
            for (AnthropicContentBlock block : a.content()) {
                if ("text".equals(block.type()) && block.text() != null) {
                    text = block.text();
                    break;
                }
            }
        }
        String role = a.role() != null ? a.role() : "assistant";
        String finishReason = mapStopReason(a.stopReason());
        Usage usage = null;
        if (a.usage() != null) {
            int prompt = a.usage().inputTokens() != null ? a.usage().inputTokens() : 0;
            int completion = a.usage().outputTokens() != null ? a.usage().outputTokens() : 0;
            usage = Usage.of(prompt, completion);
        }
        ChatResponse.Choice choice = new ChatResponse.Choice(
                0, new ChatMessage(role, text), finishReason);
        return new ChatResponse(
                a.id(),
                "chat.completion",
                Instant.now().getEpochSecond(),
                a.model(),
                List.of(choice),
                usage
        );
    }

    static String mapStopReason(String stopReason) {
        if (stopReason == null) return null;
        return switch (stopReason) {
            case "end_turn", "stop_sequence" -> "stop";
            case "max_tokens" -> "length";
            case "tool_use" -> "tool_calls";
            default -> stopReason;
        };
    }

    private Mono<? extends Throwable> mapErrorStatus(ClientResponse response) {
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
        if (t instanceof ProviderException pe) return pe;
        if (t instanceof TimeoutException) {
            return new ProviderException(
                    ProviderException.Code.TIMEOUT, props.id(),
                    "Request to " + props.id() + " timed out", t);
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

    // ─── Anthropic wire DTOs (private to this adapter) ───────────────────────

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record AnthropicMessagesRequest(
            String model,
            String system,
            List<ChatMessage> messages,
            @JsonProperty("max_tokens") Integer maxTokens,
            Double temperature,
            @JsonProperty("top_p") Double topP,
            @JsonProperty("stop_sequences") List<String> stopSequences
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record AnthropicMessagesResponse(
            String id,
            String type,
            String role,
            String model,
            List<AnthropicContentBlock> content,
            @JsonProperty("stop_reason") String stopReason,
            @JsonProperty("stop_sequence") String stopSequence,
            AnthropicUsage usage
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record AnthropicContentBlock(String type, String text) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record AnthropicUsage(
            @JsonProperty("input_tokens") Integer inputTokens,
            @JsonProperty("output_tokens") Integer outputTokens
    ) {}
}
