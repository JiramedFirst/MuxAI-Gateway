package com.muxai.gateway.api;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "spring.config.import=optional:classpath:/non-existent-test.yml"
})
class ToolCallingIT {

    static WireMockServer openAiUpstream;
    static WireMockServer anthropicUpstream;

    @LocalServerPort
    int port;

    @BeforeAll
    static void startWiremocks() {
        openAiUpstream = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        anthropicUpstream = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        openAiUpstream.start();
        anthropicUpstream.start();
    }

    @AfterAll
    static void stopWiremocks() {
        openAiUpstream.stop();
        anthropicUpstream.stop();
    }

    @AfterEach
    void reset() {
        openAiUpstream.resetAll();
        anthropicUpstream.resetAll();
    }

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry r) {
        r.add("muxai.providers[0].id", () -> "openai-p");
        r.add("muxai.providers[0].type", () -> "openai");
        r.add("muxai.providers[0].base-url", () -> openAiUpstream.baseUrl());
        r.add("muxai.providers[0].api-key", () -> "sk-oai");
        r.add("muxai.providers[0].timeout-ms", () -> "5000");
        r.add("muxai.providers[0].models[0]", () -> "gpt-4o");

        r.add("muxai.providers[1].id", () -> "anthropic-p");
        r.add("muxai.providers[1].type", () -> "anthropic");
        r.add("muxai.providers[1].base-url", () -> anthropicUpstream.baseUrl());
        r.add("muxai.providers[1].api-key", () -> "sk-ant");
        r.add("muxai.providers[1].timeout-ms", () -> "5000");
        r.add("muxai.providers[1].models[0]", () -> "claude-opus-4-7");

        r.add("muxai.routes[0].match.model", () -> "gpt-4o");
        r.add("muxai.routes[0].primary.provider", () -> "openai-p");
        r.add("muxai.routes[0].primary.model", () -> "gpt-4o");

        r.add("muxai.routes[1].match.model", () -> "claude-opus-4-7");
        r.add("muxai.routes[1].primary.provider", () -> "anthropic-p");
        r.add("muxai.routes[1].primary.model", () -> "claude-opus-4-7");

        r.add("muxai.api-keys[0].key", () -> "mgw_test_devkey");
        r.add("muxai.api-keys[0].app-id", () -> "dev");
        r.add("muxai.api-keys[0].rate-limit-per-min", () -> "1000");
    }

    private RestTemplate rest() {
        return new RestTemplate();
    }

    private HttpHeaders authHeaders(String key) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        if (key != null) h.setBearerAuth(key);
        return h;
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private static final List<Map<String, Object>> TOOLS = List.of(Map.of(
            "type", "function",
            "function", Map.of(
                    "name", "get_weather",
                    "description", "Gets weather for a location",
                    "parameters", Map.of("type", "object", "properties", Map.of())
            )
    ));

    private static final List<Map<String, Object>> USER_MESSAGES = List.of(
            Map.of("role", "user", "content", "What is the weather in SF?")
    );

    private static final String OAI_TOOL_CALL_RESPONSE = """
            {
              "id": "chatcmpl-tc1",
              "object": "chat.completion",
              "created": 1700000000,
              "model": "gpt-4o",
              "choices": [{
                "index": 0,
                "message": {
                  "role": "assistant",
                  "content": null,
                  "tool_calls": [{
                    "id": "call_abc",
                    "type": "function",
                    "function": {"name": "get_weather", "arguments": "{\\"location\\":\\"SF\\"}"}
                  }]
                },
                "finish_reason": "tool_calls"
              }],
              "usage": {"prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15}
            }
            """;

    private static final String ANT_TOOL_USE_RESPONSE = """
            {
              "id": "msg_01",
              "type": "message",
              "role": "assistant",
              "model": "claude-opus-4-7",
              "content": [{
                "type": "tool_use",
                "id": "toolu_01",
                "name": "get_weather",
                "input": {"location": "SF"}
              }],
              "stop_reason": "tool_use",
              "usage": {"input_tokens": 10, "output_tokens": 5}
            }
            """;

    private static final String ANT_TEXT_RESPONSE = """
            {
              "id": "msg_02",
              "type": "message",
              "role": "assistant",
              "model": "claude-opus-4-7",
              "content": [{"type": "text", "text": "hello"}],
              "stop_reason": "end_turn",
              "usage": {"input_tokens": 5, "output_tokens": 3}
            }
            """;

    @Test
    void openAiPassthroughForwardsToolsUnchanged() {
        openAiUpstream.stubFor(post(urlEqualTo("/chat/completions"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(OAI_TOOL_CALL_RESPONSE)));

        rest().exchange(url("/v1/chat/completions"), HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "model", "gpt-4o",
                        "messages", USER_MESSAGES,
                        "tools", TOOLS,
                        "tool_choice", "auto"),
                        authHeaders("mgw_test_devkey")),
                String.class);

        openAiUpstream.verify(postRequestedFor(urlEqualTo("/chat/completions"))
                .withRequestBody(matchingJsonPath("$.tools[0].function.name", equalTo("get_weather")))
                .withRequestBody(matchingJsonPath("$.tool_choice", equalTo("auto"))));
    }

    @Test
    void openAiPassthroughReturnsToolCallsToClient() {
        openAiUpstream.stubFor(post(urlEqualTo("/chat/completions"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(OAI_TOOL_CALL_RESPONSE)));

        ResponseEntity<String> resp = rest().exchange(
                url("/v1/chat/completions"), HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "model", "gpt-4o",
                        "messages", USER_MESSAGES,
                        "tools", TOOLS,
                        "tool_choice", "auto"),
                        authHeaders("mgw_test_devkey")),
                String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody())
                .contains("\"tool_calls\"")
                .contains("\"call_abc\"")
                .contains("\"get_weather\"")
                .contains("\"finish_reason\":\"tool_calls\"");
    }

    @Test
    void anthropicToolsTranslatedToNativeFormat() {
        anthropicUpstream.stubFor(post(urlEqualTo("/messages"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(ANT_TOOL_USE_RESPONSE)));

        rest().exchange(url("/v1/chat/completions"), HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "model", "claude-opus-4-7",
                        "messages", USER_MESSAGES,
                        "tools", TOOLS,
                        "tool_choice", "auto"),
                        authHeaders("mgw_test_devkey")),
                String.class);

        anthropicUpstream.verify(postRequestedFor(urlEqualTo("/messages"))
                .withRequestBody(matchingJsonPath("$.tools[0].name", equalTo("get_weather")))
                .withRequestBody(matchingJsonPath("$.tools[0].input_schema.type", equalTo("object")))
                .withRequestBody(matchingJsonPath("$.tool_choice.type", equalTo("auto"))));
    }

    @Test
    void anthropicToolUseTranslatedToOpenAiToolCalls() {
        anthropicUpstream.stubFor(post(urlEqualTo("/messages"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(ANT_TOOL_USE_RESPONSE)));

        ResponseEntity<String> resp = rest().exchange(
                url("/v1/chat/completions"), HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "model", "claude-opus-4-7",
                        "messages", USER_MESSAGES,
                        "tools", TOOLS,
                        "tool_choice", "auto"),
                        authHeaders("mgw_test_devkey")),
                String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody())
                .contains("\"tool_calls\"")
                .contains("\"toolu_01\"")
                .contains("\"get_weather\"")
                .contains("\"finish_reason\":\"tool_calls\"")
                .contains("location");
    }

    @Test
    void anthropicToolChoiceRequiredMapsToAny() {
        anthropicUpstream.stubFor(post(urlEqualTo("/messages"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(ANT_TOOL_USE_RESPONSE)));

        rest().exchange(url("/v1/chat/completions"), HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "model", "claude-opus-4-7",
                        "messages", USER_MESSAGES,
                        "tools", TOOLS,
                        "tool_choice", "required"),
                        authHeaders("mgw_test_devkey")),
                String.class);

        anthropicUpstream.verify(postRequestedFor(urlEqualTo("/messages"))
                .withRequestBody(matchingJsonPath("$.tool_choice.type", equalTo("any"))));
    }

    @Test
    void anthropicToolChoiceNoneOmitsField() {
        anthropicUpstream.stubFor(post(urlEqualTo("/messages"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(ANT_TEXT_RESPONSE)));

        ResponseEntity<String> resp = rest().exchange(
                url("/v1/chat/completions"), HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "model", "claude-opus-4-7",
                        "messages", USER_MESSAGES,
                        "tools", TOOLS,
                        "tool_choice", "none"),
                        authHeaders("mgw_test_devkey")),
                String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        // translateToolChoice("none") returns null → @JsonInclude(NON_NULL) omits the field
        anthropicUpstream.verify(1, postRequestedFor(urlEqualTo("/messages")));
    }

    @Test
    void anthropicSpecificFunctionToolChoiceMapsToToolType() {
        anthropicUpstream.stubFor(post(urlEqualTo("/messages"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(ANT_TOOL_USE_RESPONSE)));

        rest().exchange(url("/v1/chat/completions"), HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "model", "claude-opus-4-7",
                        "messages", USER_MESSAGES,
                        "tools", TOOLS,
                        "tool_choice", Map.of(
                                "type", "function",
                                "function", Map.of("name", "get_weather"))),
                        authHeaders("mgw_test_devkey")),
                String.class);

        anthropicUpstream.verify(postRequestedFor(urlEqualTo("/messages"))
                .withRequestBody(matchingJsonPath("$.tool_choice.type", equalTo("tool")))
                .withRequestBody(matchingJsonPath("$.tool_choice.name", equalTo("get_weather"))));
    }
}
