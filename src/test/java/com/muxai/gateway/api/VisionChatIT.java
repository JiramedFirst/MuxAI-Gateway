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
class VisionChatIT {

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

    private static List<Map<String, Object>> visionMessages(String imageUrl) {
        return List.of(Map.of(
                "role", "user",
                "content", List.of(
                        Map.of("type", "text", "text", "What is this?"),
                        Map.of("type", "image_url", "image_url", Map.of("url", imageUrl))
                )
        ));
    }

    private static final String OAI_VISION_OK = """
            {
              "id": "chatcmpl-vis1",
              "object": "chat.completion",
              "created": 1700000000,
              "model": "gpt-4o",
              "choices": [{
                "index": 0,
                "message": {"role": "assistant", "content": "I see a cat."},
                "finish_reason": "stop"
              }],
              "usage": {"prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15}
            }
            """;

    private static final String ANT_VISION_OK = """
            {
              "id": "msg_vis1",
              "type": "message",
              "role": "assistant",
              "model": "claude-opus-4-7",
              "content": [{"type": "text", "text": "I see a cat."}],
              "stop_reason": "end_turn",
              "usage": {"input_tokens": 10, "output_tokens": 5}
            }
            """;

    @Test
    void openAiPassthroughForwardsDataUriUnchanged() {
        openAiUpstream.stubFor(post(urlEqualTo("/chat/completions"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(OAI_VISION_OK)));

        rest().exchange(url("/v1/chat/completions"), HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "model", "gpt-4o",
                        "messages", visionMessages("data:image/png;base64,ABC123")),
                        authHeaders("mgw_test_devkey")),
                String.class);

        openAiUpstream.verify(postRequestedFor(urlEqualTo("/chat/completions"))
                .withRequestBody(matchingJsonPath(
                        "$.messages[0].content[1].image_url.url",
                        equalTo("data:image/png;base64,ABC123"))));
    }

    @Test
    void openAiPassthroughForwardsHttpsUrlUnchanged() {
        openAiUpstream.stubFor(post(urlEqualTo("/chat/completions"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(OAI_VISION_OK)));

        rest().exchange(url("/v1/chat/completions"), HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "model", "gpt-4o",
                        "messages", visionMessages("https://example.com/cat.png")),
                        authHeaders("mgw_test_devkey")),
                String.class);

        openAiUpstream.verify(postRequestedFor(urlEqualTo("/chat/completions"))
                .withRequestBody(matchingJsonPath(
                        "$.messages[0].content[1].image_url.url",
                        equalTo("https://example.com/cat.png"))));
    }

    @Test
    void anthropicDataUriTranslatedToBase64Block() {
        anthropicUpstream.stubFor(post(urlEqualTo("/messages"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(ANT_VISION_OK)));

        rest().exchange(url("/v1/chat/completions"), HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "model", "claude-opus-4-7",
                        "messages", visionMessages("data:image/png;base64,ABC123")),
                        authHeaders("mgw_test_devkey")),
                String.class);

        anthropicUpstream.verify(postRequestedFor(urlEqualTo("/messages"))
                .withRequestBody(matchingJsonPath("$.messages[0].content[1].type", equalTo("image")))
                .withRequestBody(matchingJsonPath("$.messages[0].content[1].source.type", equalTo("base64")))
                .withRequestBody(matchingJsonPath("$.messages[0].content[1].source.media_type", equalTo("image/png")))
                .withRequestBody(matchingJsonPath("$.messages[0].content[1].source.data", equalTo("ABC123"))));
    }

    @Test
    void anthropicJpegMediaTypePreserved() {
        anthropicUpstream.stubFor(post(urlEqualTo("/messages"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(ANT_VISION_OK)));

        rest().exchange(url("/v1/chat/completions"), HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "model", "claude-opus-4-7",
                        "messages", visionMessages("data:image/jpeg;base64,DEADBEEF")),
                        authHeaders("mgw_test_devkey")),
                String.class);

        anthropicUpstream.verify(postRequestedFor(urlEqualTo("/messages"))
                .withRequestBody(matchingJsonPath("$.messages[0].content[1].source.media_type", equalTo("image/jpeg")))
                .withRequestBody(matchingJsonPath("$.messages[0].content[1].source.data", equalTo("DEADBEEF"))));
    }

    @Test
    void anthropicHttpsUrlTranslatedToUrlBlock() {
        anthropicUpstream.stubFor(post(urlEqualTo("/messages"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(ANT_VISION_OK)));

        rest().exchange(url("/v1/chat/completions"), HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "model", "claude-opus-4-7",
                        "messages", visionMessages("https://example.com/cat.png")),
                        authHeaders("mgw_test_devkey")),
                String.class);

        anthropicUpstream.verify(postRequestedFor(urlEqualTo("/messages"))
                .withRequestBody(matchingJsonPath("$.messages[0].content[1].source.type", equalTo("url")))
                .withRequestBody(matchingJsonPath("$.messages[0].content[1].source.url", equalTo("https://example.com/cat.png"))));
    }

    @Test
    void anthropicVisionResponseTranslatedToOpenAiShape() {
        anthropicUpstream.stubFor(post(urlEqualTo("/messages"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(ANT_VISION_OK)));

        ResponseEntity<String> resp = rest().exchange(
                url("/v1/chat/completions"), HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "model", "claude-opus-4-7",
                        "messages", visionMessages("data:image/png;base64,ABC123")),
                        authHeaders("mgw_test_devkey")),
                String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody())
                .contains("I see a cat.")
                .contains("\"chat.completion\"")
                .contains("\"finish_reason\":\"stop\"");
    }

    @Test
    void anthropicSystemMessageExtractedToTopLevelField() {
        anthropicUpstream.stubFor(post(urlEqualTo("/messages"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(ANT_VISION_OK)));

        List<Map<String, Object>> messages = List.of(
                Map.of("role", "system", "content", "You are a vision assistant."),
                Map.of("role", "user", "content", List.of(
                        Map.of("type", "text", "text", "What is this?"),
                        Map.of("type", "image_url", "image_url", Map.of("url", "data:image/png;base64,ABC123"))
                ))
        );

        rest().exchange(url("/v1/chat/completions"), HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "model", "claude-opus-4-7",
                        "messages", messages),
                        authHeaders("mgw_test_devkey")),
                String.class);

        // System message is extracted to top-level "system" field; only user message in messages array
        anthropicUpstream.verify(postRequestedFor(urlEqualTo("/messages"))
                .withRequestBody(matchingJsonPath("$.system", equalTo("You are a vision assistant.")))
                .withRequestBody(matchingJsonPath("$.messages[0].role", equalTo("user"))));
    }
}
