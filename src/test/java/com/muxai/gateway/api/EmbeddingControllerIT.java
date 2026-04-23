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

import java.util.HashMap;
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
class EmbeddingControllerIT {

    static WireMockServer upstream;

    @LocalServerPort
    int port;

    @BeforeAll
    static void startWiremock() {
        upstream = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        upstream.start();
    }

    @AfterAll
    static void stopWiremock() {
        upstream.stop();
    }

    @AfterEach
    void reset() {
        upstream.resetAll();
    }

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry r) {
        r.add("muxai.providers[0].id", () -> "embed-p");
        r.add("muxai.providers[0].type", () -> "openai");
        r.add("muxai.providers[0].base-url", () -> upstream.baseUrl());
        r.add("muxai.providers[0].api-key", () -> "sk-embed");
        r.add("muxai.providers[0].timeout-ms", () -> "5000");
        r.add("muxai.providers[0].models[0]", () -> "text-embedding-3-small");

        r.add("muxai.routes[0].match.model", () -> "text-embedding-3-small");
        r.add("muxai.routes[0].primary.provider", () -> "embed-p");
        r.add("muxai.routes[0].primary.model", () -> "text-embedding-3-small");

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

    private static final String EMBED_OK = """
            {
              "object": "list",
              "data": [{"index": 0, "object": "embedding", "embedding": [0.1, 0.2, 0.3]}],
              "model": "text-embedding-3-small",
              "usage": {"prompt_tokens": 8, "total_tokens": 8}
            }
            """;

    @Test
    void unauthenticatedRequestIs401() throws Exception {
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection)
                java.net.URI.create(url("/v1/embeddings")).toURL().openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        conn.getOutputStream().write(
                "{\"model\":\"text-embedding-3-small\",\"input\":\"hello\"}".getBytes());

        assertThat(conn.getResponseCode()).isEqualTo(401);
    }

    @Test
    void missingModelReturns400() {
        Map<String, Object> body = new HashMap<>();
        body.put("input", "hello world");
        try {
            rest().exchange(url("/v1/embeddings"), HttpMethod.POST,
                    new HttpEntity<>(body, authHeaders("mgw_test_devkey")),
                    String.class);
            throw new AssertionError("expected 400");
        } catch (HttpStatusCodeException ex) {
            assertThat(ex.getStatusCode().value()).isEqualTo(400);
        }
    }

    @Test
    void missingInputReturns400() {
        Map<String, Object> body = new HashMap<>();
        body.put("model", "text-embedding-3-small");
        try {
            rest().exchange(url("/v1/embeddings"), HttpMethod.POST,
                    new HttpEntity<>(body, authHeaders("mgw_test_devkey")),
                    String.class);
            throw new AssertionError("expected 400");
        } catch (HttpStatusCodeException ex) {
            assertThat(ex.getStatusCode().value()).isEqualTo(400);
        }
    }

    @Test
    void singleStringInputReturns200() {
        upstream.stubFor(post(urlEqualTo("/embeddings"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(EMBED_OK)));

        ResponseEntity<String> resp = rest().exchange(
                url("/v1/embeddings"), HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "model", "text-embedding-3-small",
                        "input", "hello world"),
                        authHeaders("mgw_test_devkey")),
                String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        upstream.verify(postRequestedFor(urlEqualTo("/embeddings"))
                .withRequestBody(matchingJsonPath("$.input[0]", equalTo("hello world"))));
    }

    @Test
    void arrayInputReturns200() {
        upstream.stubFor(post(urlEqualTo("/embeddings"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(EMBED_OK)));

        ResponseEntity<String> resp = rest().exchange(
                url("/v1/embeddings"), HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "model", "text-embedding-3-small",
                        "input", List.of("foo", "bar")),
                        authHeaders("mgw_test_devkey")),
                String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        upstream.verify(postRequestedFor(urlEqualTo("/embeddings"))
                .withRequestBody(matchingJsonPath("$.input[0]", equalTo("foo")))
                .withRequestBody(matchingJsonPath("$.input[1]", equalTo("bar"))));
    }

    @Test
    void unknownModelReturns400() {
        try {
            rest().exchange(url("/v1/embeddings"), HttpMethod.POST,
                    new HttpEntity<>(Map.of(
                            "model", "does-not-exist",
                            "input", "hello"),
                            authHeaders("mgw_test_devkey")),
                    String.class);
            throw new AssertionError("expected error response");
        } catch (HttpStatusCodeException ex) {
            assertThat(ex.getStatusCode().value()).isEqualTo(400);
            assertThat(ex.getResponseBodyAsString()).contains("No route matches");
        }
    }
}
