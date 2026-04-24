package com.muxai.gateway.provider.anthropic;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.muxai.gateway.config.ProviderProperties;
import com.muxai.gateway.provider.LlmProvider;
import com.muxai.gateway.provider.ProviderCapabilities;
import com.muxai.gateway.provider.ProviderException;
import com.muxai.gateway.provider.model.ChatChunk;
import com.muxai.gateway.provider.model.ChatMessage;
import com.muxai.gateway.provider.model.ChatRequest;
import com.muxai.gateway.provider.model.ChatResponse;
import com.muxai.gateway.provider.model.ContentPart;
import com.muxai.gateway.provider.model.Tool;
import com.muxai.gateway.provider.model.ToolCall;
import com.muxai.gateway.provider.model.Usage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.ConnectException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

public class AnthropicProvider implements LlmProvider {

    static final int DEFAULT_MAX_TOKENS = 4096;
    private static final Logger log = LoggerFactory.getLogger(AnthropicProvider.class);

    private final ProviderProperties props;
    private final WebClient http;
    private final ObjectMapper mapper;

    public AnthropicProvider(ProviderProperties props, WebClient http, ObjectMapper mapper) {
        this.props = props;
        this.http = http;
        this.mapper = mapper;
    }

    @Override public String id() { return props.id(); }
    @Override public String type() { return "anthropic"; }
    @Override public boolean supports(String model) { return props.modelsOrEmpty().contains(model); }
    @Override public ProviderCapabilities capabilities() { return ProviderCapabilities.anthropicFull(); }

    @Override
    public Mono<ChatResponse> chat(ChatRequest request) {
        if (props.apiKey() == null || props.apiKey().isBlank()) {
            return Mono.error(new ProviderException(
                    ProviderException.Code.AUTH_FAILED, props.id(),
                    "Provider '" + props.id() + "' has no API key configured"));
        }
        AnthropicMessagesRequest body = toAnthropic(request, false);

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

    @Override
    public Flux<ChatChunk> chatStream(ChatRequest request) {
        if (props.apiKey() == null || props.apiKey().isBlank()) {
            return Flux.error(new ProviderException(
                    ProviderException.Code.AUTH_FAILED, props.id(),
                    "Provider '" + props.id() + "' has no API key configured"));
        }
        AnthropicMessagesRequest body = toAnthropic(request, true);

        ParameterizedTypeReference<ServerSentEvent<String>> type = new ParameterizedTypeReference<>() {};
        String chunkId = "chatcmpl-" + UUID.randomUUID();
        long created = Instant.now().getEpochSecond();
        AtomicReference<String> model = new AtomicReference<>(request.model());
        Map<Integer, ToolUseAccumulator> toolBlocks = new ConcurrentHashMap<>();

        return http.post()
                .uri("/messages")
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(body)
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::mapErrorStatus)
                .bodyToFlux(type)
                .timeout(Duration.ofMillis(props.timeoutMsOrDefault()))
                .concatMap(evt -> translateStreamEvent(evt, chunkId, created, model, toolBlocks))
                .onErrorMap(this::translate);
    }

    // ─── Translation: internal → Anthropic native ────────────────────────────

    AnthropicMessagesRequest toAnthropic(ChatRequest req, boolean stream) {
        String system = null;
        List<AnthropicMessage> conversation = new ArrayList<>();
        if (req.messages() != null) {
            for (ChatMessage m : req.messages()) {
                if ("system".equals(m.role())) {
                    String text = m.contentAsText();
                    if (text != null && !text.isEmpty()) {
                        system = (system == null) ? text : (system + "\n\n" + text);
                    }
                } else {
                    conversation.add(translateMessage(m));
                }
            }
        }
        int maxTokens = req.maxTokens() != null ? req.maxTokens() : DEFAULT_MAX_TOKENS;

        List<AnthropicTool> tools = null;
        if (req.tools() != null && !req.tools().isEmpty()) {
            tools = new ArrayList<>();
            for (Tool t : req.tools()) {
                if (t == null || t.function() == null) continue;
                tools.add(new AnthropicTool(
                        t.function().name(),
                        t.function().description(),
                        t.function().parameters()));
            }
            if (tools.isEmpty()) tools = null;
        }

        Object toolChoice = translateToolChoice(req.toolChoice());

        return new AnthropicMessagesRequest(
                req.model(), system, conversation, maxTokens,
                req.temperature(), req.topP(), req.stop(), tools, toolChoice,
                stream ? Boolean.TRUE : null);
    }

    AnthropicMessage translateMessage(ChatMessage m) {
        String role = mapRole(m.role());

        // Tool result (OpenAI role=tool) → Anthropic user message with tool_result block
        if ("tool".equals(m.role()) && m.toolCallId() != null) {
            Map<String, Object> block = new LinkedHashMap<>();
            block.put("type", "tool_result");
            block.put("tool_use_id", m.toolCallId());
            block.put("content", m.contentAsText() == null ? "" : m.contentAsText());
            return new AnthropicMessage("user", List.of(block));
        }

        // Assistant with tool_calls → content array mixing text and tool_use blocks
        if ("assistant".equals(m.role()) && m.toolCalls() != null && !m.toolCalls().isEmpty()) {
            List<Object> blocks = new ArrayList<>();
            String text = m.contentAsText();
            if (text != null && !text.isEmpty()) {
                blocks.add(Map.of("type", "text", "text", text));
            }
            for (ToolCall tc : m.toolCalls()) {
                if (tc == null || tc.function() == null) continue;
                Map<String, Object> block = new LinkedHashMap<>();
                block.put("type", "tool_use");
                block.put("id", tc.id() != null ? tc.id() : "toolu_" + UUID.randomUUID());
                block.put("name", tc.function().name());
                block.put("input", parseJsonOrEmpty(tc.function().arguments()));
                blocks.add(block);
            }
            return new AnthropicMessage(role, blocks);
        }

        Object content = m.content();
        if (content instanceof String s) {
            return new AnthropicMessage(role, s);
        }
        if (content instanceof List<?> list) {
            List<Object> blocks = new ArrayList<>();
            for (Object item : list) {
                Object block = translateContentPart(item);
                if (block != null) blocks.add(block);
            }
            return new AnthropicMessage(role, blocks);
        }
        return new AnthropicMessage(role, m.contentAsText() != null ? m.contentAsText() : "");
    }

    private Object translateContentPart(Object part) {
        String type;
        Object textVal = null;
        Object imageUrl = null;
        Object cacheControl = null;
        if (part instanceof Map<?, ?> map) {
            type = map.get("type") instanceof String t ? t : null;
            textVal = map.get("text");
            imageUrl = map.get("image_url");
            cacheControl = map.get("cache_control");
        } else if (part instanceof ContentPart cp) {
            type = cp.type();
            textVal = cp.text();
            imageUrl = cp.imageUrl();
        } else {
            return null;
        }

        if ("text".equals(type)) {
            Map<String, Object> block = new LinkedHashMap<>();
            block.put("type", "text");
            block.put("text", textVal == null ? "" : textVal.toString());
            if (cacheControl != null) block.put("cache_control", cacheControl);
            return block;
        }
        if ("image_url".equals(type)) {
            String url = extractImageUrl(imageUrl);
            if (url == null) return null;
            Map<String, Object> block = new LinkedHashMap<>();
            block.put("type", "image");
            block.put("source", imageSourceOf(url));
            if (cacheControl != null) block.put("cache_control", cacheControl);
            return block;
        }
        return null;
    }

    private static String extractImageUrl(Object imageUrl) {
        if (imageUrl instanceof String s) return s;
        if (imageUrl instanceof Map<?, ?> map) {
            Object url = map.get("url");
            return url instanceof String s ? s : null;
        }
        if (imageUrl instanceof ContentPart.ImageUrl iu) return iu.url();
        return null;
    }

    static Map<String, Object> imageSourceOf(String url) {
        String u = url.trim();
        if (u.startsWith("data:")) {
            int comma = u.indexOf(',');
            int semi = u.indexOf(';');
            String mediaType = "image/png";
            if (semi > 5) mediaType = u.substring(5, semi);
            String data = comma >= 0 ? u.substring(comma + 1) : "";
            return Map.of("type", "base64", "media_type", mediaType, "data", data);
        }
        return Map.of("type", "url", "url", u);
    }

    private Object parseJsonOrEmpty(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return mapper.readValue(json, Object.class);
        } catch (Exception e) {
            return Map.of("_raw", json);
        }
    }

    private static String mapRole(String openAiRole) {
        if (openAiRole == null) return "user";
        return switch (openAiRole) {
            case "assistant" -> "assistant";
            case "user", "tool" -> "user";
            default -> "user";
        };
    }

    static Object translateToolChoice(Object openAiChoice) {
        if (openAiChoice == null) return null;
        if (openAiChoice instanceof String s) {
            return switch (s.toLowerCase(Locale.ROOT)) {
                case "auto" -> Map.of("type", "auto");
                case "none" -> null;
                case "required" -> Map.of("type", "any");
                default -> Map.of("type", "auto");
            };
        }
        if (openAiChoice instanceof Map<?, ?> map) {
            Object fn = map.get("function");
            if (fn instanceof Map<?, ?> fnMap) {
                Object name = fnMap.get("name");
                if (name instanceof String s) {
                    return Map.of("type", "tool", "name", s);
                }
            }
        }
        return null;
    }

    // ─── Translation: Anthropic native → internal ────────────────────────────

    ChatResponse toOpenAi(AnthropicMessagesResponse a) {
        StringBuilder text = new StringBuilder();
        List<ToolCall> toolCalls = new ArrayList<>();
        if (a.content() != null) {
            for (AnthropicContentBlock block : a.content()) {
                if ("text".equals(block.type()) && block.text() != null) {
                    if (text.length() > 0) text.append('\n');
                    text.append(block.text());
                } else if ("tool_use".equals(block.type())) {
                    String args;
                    try {
                        args = mapper.writeValueAsString(block.input() != null ? block.input() : Map.of());
                    } catch (Exception e) {
                        args = "{}";
                    }
                    toolCalls.add(new ToolCall(
                            block.id(), "function",
                            new ToolCall.FunctionCall(block.name(), args)));
                }
            }
        }
        String role = a.role() != null ? a.role() : "assistant";
        String finishReason = mapStopReason(a.stopReason(), !toolCalls.isEmpty());
        Usage usage = null;
        if (a.usage() != null) {
            int prompt = a.usage().inputTokens() != null ? a.usage().inputTokens() : 0;
            int completion = a.usage().outputTokens() != null ? a.usage().outputTokens() : 0;
            usage = Usage.of(prompt, completion);
        }
        ChatMessage message = new ChatMessage(role, text.toString(),
                toolCalls.isEmpty() ? null : toolCalls, null, null);
        ChatResponse.Choice choice = new ChatResponse.Choice(0, message, finishReason);
        return new ChatResponse(
                a.id(), "chat.completion",
                Instant.now().getEpochSecond(),
                a.model(), List.of(choice), usage);
    }

    static String mapStopReason(String stopReason, boolean hasToolCalls) {
        if (hasToolCalls) return "tool_calls";
        if (stopReason == null) return null;
        return switch (stopReason) {
            case "end_turn", "stop_sequence" -> "stop";
            case "max_tokens" -> "length";
            case "tool_use" -> "tool_calls";
            default -> stopReason;
        };
    }

    // ─── Streaming event translation ─────────────────────────────────────────

    private Flux<ChatChunk> translateStreamEvent(ServerSentEvent<String> event,
                                                 String chunkId,
                                                 long created,
                                                 AtomicReference<String> model,
                                                 Map<Integer, ToolUseAccumulator> toolBlocks) {
        String evtType = event.event();
        String data = event.data();
        if (data == null || data.isEmpty()) return Flux.empty();
        try {
            Map<String, Object> node = mapper.readValue(data, new TypeReference<>() {});
            String type = evtType != null ? evtType : String.valueOf(node.get("type"));
            return switch (type) {
                case "message_start" -> {
                    Object msg = node.get("message");
                    if (msg instanceof Map<?, ?> m) {
                        Object mdl = m.get("model");
                        if (mdl instanceof String s) model.set(s);
                    }
                    ChatMessage delta = new ChatMessage("assistant", "");
                    yield Flux.just(chunk(chunkId, created, model.get(), delta, null));
                }
                case "content_block_start" -> {
                    Object cb = node.get("content_block");
                    Integer idx = toInt(node.get("index"));
                    if (cb instanceof Map<?, ?> m && "tool_use".equals(m.get("type")) && idx != null) {
                        ToolUseAccumulator acc = new ToolUseAccumulator(
                                stringVal(m.get("id")), stringVal(m.get("name")));
                        toolBlocks.put(idx, acc);
                        ToolCall tc = new ToolCall(acc.id, "function",
                                new ToolCall.FunctionCall(acc.name, ""));
                        ChatMessage delta = new ChatMessage(null, null, List.of(tc), null, null);
                        yield Flux.just(chunk(chunkId, created, model.get(), delta, null));
                    }
                    yield Flux.empty();
                }
                case "content_block_delta" -> {
                    Object delta = node.get("delta");
                    Integer idx = toInt(node.get("index"));
                    if (delta instanceof Map<?, ?> d) {
                        String dt = stringVal(d.get("type"));
                        if ("text_delta".equals(dt)) {
                            String t = stringVal(d.get("text"));
                            if (t == null) yield Flux.empty();
                            ChatMessage m = new ChatMessage(null, t);
                            yield Flux.just(chunk(chunkId, created, model.get(), m, null));
                        }
                        if ("input_json_delta".equals(dt) && idx != null) {
                            ToolUseAccumulator acc = toolBlocks.get(idx);
                            if (acc == null) yield Flux.empty();
                            String partial = stringVal(d.get("partial_json"));
                            if (partial == null) yield Flux.empty();
                            ToolCall tc = new ToolCall(null, "function",
                                    new ToolCall.FunctionCall(null, partial));
                            ChatMessage m = new ChatMessage(null, null, List.of(tc), null, null);
                            yield Flux.just(chunk(chunkId, created, model.get(), m, null));
                        }
                    }
                    yield Flux.empty();
                }
                case "message_delta" -> {
                    Object delta = node.get("delta");
                    if (delta instanceof Map<?, ?> d) {
                        String stop = stringVal(d.get("stop_reason"));
                        String finish = mapStopReason(stop, !toolBlocks.isEmpty());
                        if (finish != null) {
                            yield Flux.just(chunk(chunkId, created, model.get(), null, finish));
                        }
                    }
                    yield Flux.empty();
                }
                case "message_stop", "ping", "content_block_stop" -> Flux.empty();
                case "error" -> Flux.error(new ProviderException(
                        ProviderException.Code.PROVIDER_ERROR, props.id(),
                        "Anthropic stream error: " + data));
                default -> Flux.empty();
            };
        } catch (Exception e) {
            log.warn("Failed to parse Anthropic stream event '{}': {}", evtType, e.getMessage());
            return Flux.empty();
        }
    }

    private static ChatChunk chunk(String id, long created, String model,
                                   ChatMessage delta, String finishReason) {
        return new ChatChunk(
                id, "chat.completion.chunk", created, model,
                List.of(new ChatChunk.Delta(0, delta, finishReason)));
    }

    private static Integer toInt(Object o) {
        if (o instanceof Integer i) return i;
        if (o instanceof Number n) return n.intValue();
        return null;
    }

    private static String stringVal(Object o) {
        return o instanceof String s ? s : null;
    }

    private static final class ToolUseAccumulator {
        final String id;
        final String name;
        ToolUseAccumulator(String id, String name) { this.id = id; this.name = name; }
    }

    // ─── Error mapping ───────────────────────────────────────────────────────

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
            List<AnthropicMessage> messages,
            @JsonProperty("max_tokens") Integer maxTokens,
            Double temperature,
            @JsonProperty("top_p") Double topP,
            @JsonProperty("stop_sequences") List<String> stopSequences,
            List<AnthropicTool> tools,
            @JsonProperty("tool_choice") Object toolChoice,
            Boolean stream
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record AnthropicMessage(String role, Object content) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record AnthropicTool(String name, String description,
                         @JsonProperty("input_schema") Object inputSchema) {}

    public static final class AnthropicMessagesResponse {
        public String id;
        public String type;
        public String role;
        public String model;
        public List<AnthropicContentBlock> content;
        @JsonProperty("stop_reason") public String stopReason;
        @JsonProperty("stop_sequence") public String stopSequence;
        public AnthropicUsage usage;

        public String id() { return id; }
        public String type() { return type; }
        public String role() { return role; }
        public String model() { return model; }
        public List<AnthropicContentBlock> content() { return content; }
        public String stopReason() { return stopReason; }
        public String stopSequence() { return stopSequence; }
        public AnthropicUsage usage() { return usage; }
    }

    public static final class AnthropicContentBlock {
        public String type;
        public String text;
        public String id;
        public String name;
        public Object input;
        private final Map<String, Object> extras = new LinkedHashMap<>();

        public String type() { return type; }
        public String text() { return text; }
        public String id() { return id; }
        public String name() { return name; }
        public Object input() { return input; }

        @JsonAnySetter public void set(String k, Object v) { extras.put(k, v); }
        @JsonAnyGetter public Map<String, Object> any() { return extras; }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AnthropicUsage(
            @JsonProperty("input_tokens") Integer inputTokens,
            @JsonProperty("output_tokens") Integer outputTokens
    ) {}
}
