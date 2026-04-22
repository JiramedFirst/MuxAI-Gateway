package com.muxai.gateway.provider.anthropic;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.muxai.gateway.config.ProviderProperties;
import com.muxai.gateway.provider.model.ChatMessage;
import com.muxai.gateway.provider.model.ChatRequest;
import com.muxai.gateway.provider.model.ContentPart;
import com.muxai.gateway.provider.model.Tool;
import com.muxai.gateway.provider.model.ToolCall;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AnthropicTranslationTest {

    private AnthropicProvider provider() {
        ProviderProperties props = new ProviderProperties(
                "anthropic-test", "anthropic", "https://example/v1",
                "k", 60_000L, List.of("claude-sonnet-4-6"));
        return new AnthropicProviderFactory(WebClient.builder(), new ObjectMapper()).create(props);
    }

    @Test
    void toolChoiceAutoMapsToAnthropicAuto() {
        Object out = AnthropicProvider.translateToolChoice("auto");
        assertThat(out).isInstanceOf(Map.class);
        assertThat(((Map<?, ?>) out).get("type")).isEqualTo("auto");
    }

    @Test
    void toolChoiceRequiredMapsToAnthropicAny() {
        Object out = AnthropicProvider.translateToolChoice("required");
        assertThat(((Map<?, ?>) out).get("type")).isEqualTo("any");
    }

    @Test
    void toolChoiceSpecificFunctionMapsToTool() {
        Object out = AnthropicProvider.translateToolChoice(
                Map.of("type", "function", "function", Map.of("name", "get_weather")));
        Map<?, ?> m = (Map<?, ?>) out;
        assertThat(m.get("type")).isEqualTo("tool");
        assertThat(m.get("name")).isEqualTo("get_weather");
    }

    @Test
    void dataUriImageBecomesAnthropicBase64Source() {
        Map<String, Object> src = AnthropicProvider.imageSourceOf(
                "data:image/jpeg;base64,AAAA");
        assertThat(src.get("type")).isEqualTo("base64");
        assertThat(src.get("media_type")).isEqualTo("image/jpeg");
        assertThat(src.get("data")).isEqualTo("AAAA");
    }

    @Test
    void plainUrlImageBecomesAnthropicUrlSource() {
        Map<String, Object> src = AnthropicProvider.imageSourceOf("https://e.com/pic.png");
        assertThat(src.get("type")).isEqualTo("url");
        assertThat(src.get("url")).isEqualTo("https://e.com/pic.png");
    }

    @Test
    void visionContentPartsSurviveTranslation() {
        ChatMessage msg = new ChatMessage("user", List.of(
                ContentPart.text("what is this?"),
                ContentPart.image("https://example.com/pic.png")));

        AnthropicProvider.AnthropicMessage translated = provider().translateMessage(msg);
        assertThat(translated.role()).isEqualTo("user");
        List<?> blocks = (List<?>) translated.content();
        assertThat(blocks).hasSize(2);
        Map<?, ?> b0 = (Map<?, ?>) blocks.get(0);
        Map<?, ?> b1 = (Map<?, ?>) blocks.get(1);
        assertThat(b0.get("type")).isEqualTo("text");
        assertThat(b0.get("text")).isEqualTo("what is this?");
        assertThat(b1.get("type")).isEqualTo("image");
    }

    @Test
    void toolRoleMessageTranslatesToToolResultBlock() {
        ChatMessage tool = new ChatMessage("tool", "42", null, "call_abc", "get_weather");
        AnthropicProvider.AnthropicMessage translated = provider().translateMessage(tool);
        assertThat(translated.role()).isEqualTo("user");
        List<?> blocks = (List<?>) translated.content();
        Map<?, ?> b = (Map<?, ?>) blocks.get(0);
        assertThat(b.get("type")).isEqualTo("tool_result");
        assertThat(b.get("tool_use_id")).isEqualTo("call_abc");
        assertThat(b.get("content")).isEqualTo("42");
    }

    @Test
    void assistantToolCallsTranslateToToolUseBlocks() {
        ToolCall tc = new ToolCall("call_1", "function",
                new ToolCall.FunctionCall("get_weather", "{\"city\":\"Tokyo\"}"));
        ChatMessage asst = new ChatMessage("assistant", "let me check", List.of(tc), null, null);

        AnthropicProvider.AnthropicMessage translated = provider().translateMessage(asst);
        List<?> blocks = (List<?>) translated.content();
        assertThat(blocks).hasSize(2);
        Map<?, ?> textBlock = (Map<?, ?>) blocks.get(0);
        Map<?, ?> toolBlock = (Map<?, ?>) blocks.get(1);
        assertThat(textBlock.get("type")).isEqualTo("text");
        assertThat(textBlock.get("text")).isEqualTo("let me check");
        assertThat(toolBlock.get("type")).isEqualTo("tool_use");
        assertThat(toolBlock.get("id")).isEqualTo("call_1");
        assertThat(toolBlock.get("name")).isEqualTo("get_weather");
        Map<?, ?> input = (Map<?, ?>) toolBlock.get("input");
        assertThat(input.get("city")).isEqualTo("Tokyo");
    }

    @Test
    void toolsOnRequestAreCarriedIntoAnthropicShape() {
        ChatRequest req = new ChatRequest("claude-sonnet-4-6",
                List.of(new ChatMessage("user", "hi")),
                null, null, null, null, null,
                List.of(new Tool("function", new Tool.Function("get_weather",
                        "Look up the weather", Map.of("type", "object")))),
                "auto");

        AnthropicProvider.AnthropicMessagesRequest translated = provider().toAnthropic(req, false);
        assertThat(translated.tools()).hasSize(1);
        assertThat(translated.tools().get(0).name()).isEqualTo("get_weather");
        assertThat(translated.toolChoice()).isNotNull();
    }
}
