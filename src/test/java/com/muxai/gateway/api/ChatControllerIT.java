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
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        // Suppress the default providers.yml imports — the IT supplies its own via @DynamicPropertySource.
        "spring.config.import=optional:classpath:/non-existent-test.yml"
})
class ChatControllerIT {

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
        // Providers
        r.add("muxai.providers[0].id", () -> "primary");
        r.add("muxai.providers[0].type", () -> "openai");
        r.add("muxai.providers[0].base-url", () -> primary.baseUrl());
        r.add("muxai.providers[0].api-key", () -> "sk-primary");
        r.add("muxai.providers[0].timeout-ms", () -> "5000");
        r.add("muxai.providers[0].models[0]", () -> "gpt-4o");

        r.add("muxai.providers[1].id", () -> "fallback");
        r.add("muxai.providers[1].type", () -> "openai");
        r.add("muxai.providers[1].base-url", () -> fallback.baseUrl());
        r.add("muxai.providers[1].api-key", () -> "sk-fallback");
        r.add("muxai.providers[1].timeout-ms", () -> "5000");
        r.add("muxai.providers[1].models[0]", () -> "gpt-4o-mini");

        // Routes: model 'smart' goes primary with fallback; catch-all -> primary
        r.add("muxai.routes[0].match.model", () -> "smart");
        r.add("muxai.routes[0].primary.provider", () -> "primary");
        r.add("muxai.routes[0].primary.model", () -> "gpt-4o");
        r.add("muxai.routes[0].fallback[0].provider", () -> "fallback");
        r.add("muxai.routes[0].fallback[0].model", () -> "gpt-4o-mini");

        r.add("muxai.routes[1].primary.provider", () -> "primary");

        // API keys
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
              "id": "chatcmpl-1",
              "object": "chat.completion",
              "created": 1700000000,
              "model": "%s",
              "choices": [{
                "index": 0,
                "message": {"role": "assistant", "content": "%s"},
                "finish_reason": "stop"
              }],
              "usage": {"prompt_tokens": 5, "completion_tokens": 3, "total_tokens": 8}
            }
            """;

    @Test
    void unauthenticatedRequestIs401() throws Exception {
        // Use HttpURLConnection directly — RestTemplate's JDK client discards the 401
        // response body before we can read it in some environments.
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection)
                java.net.URI.create(url("/v1/chat/completions")).toURL().openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        conn.getOutputStream().write(
                "{\"model\":\"smart\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}".getBytes());

        int status = conn.getResponseCode();
        assertThat(status).isEqualTo(401);

        String body;
        try (java.io.InputStream es = conn.getErrorStream()) {
            body = es != null ? new String(es.readAllBytes()) : "";
        }
        assertThat(body).contains("\"error\"");
    }

    @Test
    void happyPathReturns200FromPrimary() {
        primary.stubFor(post(urlEqualTo("/chat/completions"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(String.format(OK_BODY, "gpt-4o", "from-primary"))));

        ResponseEntity<String> resp = rest().exchange(
                url("/v1/chat/completions"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "model", "smart",
                        "messages", java.util.List.of(Map.of("role", "user", "content", "hi"))),
                        authHeaders("mgw_test_devkey")),
                String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).contains("from-primary");
        assertThat(resp.getBody()).contains("\"object\":\"chat.completion\"");
        assertThat(resp.getBody()).contains("\"finish_reason\":\"stop\"");

        fallback.verify(0, com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor(urlEqualTo("/chat/completions")));
    }

    @Test
    void fallbackFiresWhenPrimary5xx() {
        primary.stubFor(post(urlEqualTo("/chat/completions"))
                .willReturn(aResponse().withStatus(500).withBody("upstream down")));
        fallback.stubFor(post(urlEqualTo("/chat/completions"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(String.format(OK_BODY, "gpt-4o-mini", "from-fallback"))));

        ResponseEntity<String> resp = rest().exchange(
                url("/v1/chat/completions"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "model", "smart",
                        "messages", java.util.List.of(Map.of("role", "user", "content", "hi"))),
                        authHeaders("mgw_test_devkey")),
                String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).contains("from-fallback");

        primary.verify(1, com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor(urlEqualTo("/chat/completions")));
        fallback.verify(1, com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor(urlEqualTo("/chat/completions")));
    }

    @Test
    void streamingReturns501() {
        try {
            rest().exchange(
                    url("/v1/chat/completions"),
                    HttpMethod.POST,
                    new HttpEntity<>(Map.of(
                            "model", "smart",
                            "messages", java.util.List.of(Map.of("role", "user", "content", "hi")),
                            "stream", true),
                            authHeaders("mgw_test_devkey")),
                    String.class);
            throw new AssertionError("expected 501");
        } catch (HttpStatusCodeException ex) {
            assertThat(ex.getStatusCode().value()).isEqualTo(501);
            assertThat(ex.getResponseBodyAsString()).contains("STREAMING_UNSUPPORTED");
        }
    }

    @Test
    void modelsEndpointReturnsConfiguredModels() {
        ResponseEntity<String> resp = rest().exchange(
                url("/v1/models"),
                HttpMethod.GET,
                new HttpEntity<>(authHeaders("mgw_test_devkey")),
                String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).contains("gpt-4o");
        assertThat(resp.getBody()).contains("gpt-4o-mini");
        assertThat(resp.getBody()).contains("\"object\":\"list\"");
    }
}
