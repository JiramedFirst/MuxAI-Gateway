package com.muxai.gateway.api;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
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
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
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
class OcrControllerIT {

    static WireMockServer primary;
    static WireMockServer fallback;

    @LocalServerPort
    int port;

    @BeforeAll
    static void startWiremocks() {
        primary = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        fallback = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        primary.start();
        fallback.start();
    }

    @AfterAll
    static void stopWiremocks() {
        primary.stop();
        fallback.stop();
    }

    @AfterEach
    void reset() {
        primary.resetAll();
        fallback.resetAll();
    }

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry r) {
        r.add("muxai.providers[0].id", () -> "primary");
        r.add("muxai.providers[0].type", () -> "openai");
        r.add("muxai.providers[0].base-url", () -> primary.baseUrl());
        r.add("muxai.providers[0].api-key", () -> "sk-primary");
        r.add("muxai.providers[0].timeout-ms", () -> "5000");
        r.add("muxai.providers[0].models[0]", () -> "typhoon-ocr");

        r.add("muxai.providers[1].id", () -> "fallback");
        r.add("muxai.providers[1].type", () -> "openai");
        r.add("muxai.providers[1].base-url", () -> fallback.baseUrl());
        r.add("muxai.providers[1].api-key", () -> "sk-fallback");
        r.add("muxai.providers[1].timeout-ms", () -> "5000");
        r.add("muxai.providers[1].models[0]", () -> "typhoon-ocr");

        // Route: typhoon-ocr -> primary then fallback; no catch-all so unknown models 404.
        r.add("muxai.routes[0].match.model", () -> "typhoon-ocr");
        r.add("muxai.routes[0].primary.provider", () -> "primary");
        r.add("muxai.routes[0].primary.model", () -> "typhoon-ocr");
        r.add("muxai.routes[0].fallback[0].provider", () -> "fallback");
        r.add("muxai.routes[0].fallback[0].model", () -> "typhoon-ocr");

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

    private static final String OK_BODY = """
            {
              "id": "chatcmpl-ocr",
              "object": "chat.completion",
              "created": 1700000000,
              "model": "%s",
              "choices": [{
                "index": 0,
                "message": {"role": "assistant", "content": "%s"},
                "finish_reason": "stop"
              }],
              "usage": {"prompt_tokens": 42, "completion_tokens": 7, "total_tokens": 49}
            }
            """;

    @Test
    void unauthenticatedRequestIs401() throws Exception {
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection)
                java.net.URI.create(url("/v1/ocr")).toURL().openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        conn.getOutputStream().write(
                "{\"model\":\"typhoon-ocr\",\"image\":\"ABC123\"}".getBytes());

        assertThat(conn.getResponseCode()).isEqualTo(401);
    }

    @Test
    void missingImageFieldReturns400() {
        Map<String, Object> body = new HashMap<>();
        body.put("model", "typhoon-ocr");
        // no 'image' field
        try {
            rest().exchange(url("/v1/ocr"), HttpMethod.POST,
                    new HttpEntity<>(body, authHeaders("mgw_test_devkey")),
                    String.class);
            throw new AssertionError("expected 400");
        } catch (HttpStatusCodeException ex) {
            assertThat(ex.getStatusCode().value()).isEqualTo(400);
            assertThat(ex.getResponseBodyAsString()).contains("INVALID_REQUEST");
        }
    }

    @Test
    void unknownModelReturns400WithNoRouteMatches() {
        try {
            rest().exchange(url("/v1/ocr"), HttpMethod.POST,
                    new HttpEntity<>(Map.of(
                            "model", "does-not-exist",
                            "image", "ABC123"),
                            authHeaders("mgw_test_devkey")),
                    String.class);
            throw new AssertionError("expected error response");
        } catch (HttpStatusCodeException ex) {
            assertThat(ex.getStatusCode().value()).isEqualTo(400);
            assertThat(ex.getResponseBodyAsString()).contains("No route matches");
            assertThat(ex.getResponseBodyAsString()).contains("INVALID_REQUEST");
        }
    }

    @Test
    void rawBase64ImageIsNormalizedToDataUri() {
        primary.stubFor(post(urlEqualTo("/chat/completions"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(String.format(OK_BODY, "typhoon-ocr", "hello"))));

        ResponseEntity<String> resp = rest().exchange(
                url("/v1/ocr"), HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "model", "typhoon-ocr",
                        "image", "ABC123"),
                        authHeaders("mgw_test_devkey")),
                String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        primary.verify(postRequestedFor(urlEqualTo("/chat/completions"))
                .withRequestBody(matchingJsonPath(
                        "$.messages[0].content[1].image_url.url",
                        equalTo("data:image/png;base64,ABC123"))));
    }

    @Test
    void fallbackPathPopulatesProvidersAttempted() {
        primary.stubFor(post(urlEqualTo("/chat/completions"))
                .willReturn(aResponse().withStatus(500).withBody("upstream down")));
        fallback.stubFor(post(urlEqualTo("/chat/completions"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(String.format(OK_BODY, "typhoon-ocr", "from-fallback"))));

        ResponseEntity<String> resp = rest().exchange(
                url("/v1/ocr"), HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "model", "typhoon-ocr",
                        "image", "data:image/png;base64,ABC123"),
                        authHeaders("mgw_test_devkey")),
                String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).contains("from-fallback");

        primary.verify(1, postRequestedFor(urlEqualTo("/chat/completions")));
        fallback.verify(1, postRequestedFor(urlEqualTo("/chat/completions")));
    }

    @Test
    void successReturnsModelTextAndUsage() {
        primary.stubFor(post(urlEqualTo("/chat/completions"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(String.format(OK_BODY, "typhoon-ocr", "# Extracted\\nHello"))));

        ResponseEntity<String> resp = rest().exchange(
                url("/v1/ocr"), HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "model", "typhoon-ocr",
                        "image", "data:image/png;base64,ABC123"),
                        authHeaders("mgw_test_devkey")),
                String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).contains("\"model\":\"typhoon-ocr\"");
        assertThat(resp.getBody()).contains("\"text\":");
        assertThat(resp.getBody()).contains("# Extracted");
        assertThat(resp.getBody()).contains("\"usage\":");
        assertThat(resp.getBody()).contains("\"prompt_tokens\":42");
        assertThat(resp.getBody()).contains("\"completion_tokens\":7");
    }

    @Test
    void httpSchemeInImageIsRejected() {
        try {
            rest().exchange(url("/v1/ocr"), HttpMethod.POST,
                    new HttpEntity<>(Map.of(
                            "model", "typhoon-ocr",
                            "image", "http://example.com/x.png"),
                            authHeaders("mgw_test_devkey")),
                    String.class);
            throw new AssertionError("expected 400");
        } catch (HttpStatusCodeException ex) {
            assertThat(ex.getStatusCode().value()).isEqualTo(400);
            assertThat(ex.getResponseBodyAsString()).contains("http://");
        }
        primary.verify(0, postRequestedFor(WireMock.urlEqualTo("/chat/completions")));
    }
}
