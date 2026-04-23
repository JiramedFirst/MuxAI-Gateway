package com.muxai.gateway.auth;

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

import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Verifies the per-API-key allowedModels enforcement (Sprint 1a).
 *
 * Two keys: one scoped to ["gpt-4o-mini"], one unscoped. Hits /v1/chat/completions
 * and /v1/models with each, asserting the scoped key gets 403 / filtered response
 * outside its allowed set.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "spring.config.import=optional:classpath:/non-existent-test.yml"
})
class ModelScopingIT {

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
        r.add("muxai.providers[0].id", () -> "p1");
        r.add("muxai.providers[0].type", () -> "openai");
        r.add("muxai.providers[0].base-url", () -> upstream.baseUrl());
        r.add("muxai.providers[0].api-key", () -> "sk-x");
        r.add("muxai.providers[0].models[0]", () -> "gpt-4o");
        r.add("muxai.providers[0].models[1]", () -> "gpt-4o-mini");

        r.add("muxai.routes[0].primary.provider", () -> "p1");

        // Scoped key — only gpt-4o-mini.
        r.add("muxai.api-keys[0].key", () -> "mgw_scoped");
        r.add("muxai.api-keys[0].app-id", () -> "scoped-app");
        r.add("muxai.api-keys[0].rate-limit-per-min", () -> "1000");
        r.add("muxai.api-keys[0].allowed-models[0]", () -> "gpt-4o-mini");

        // Unscoped key — no allowedModels, can hit any model.
        r.add("muxai.api-keys[1].key", () -> "mgw_unscoped");
        r.add("muxai.api-keys[1].app-id", () -> "unscoped-app");
        r.add("muxai.api-keys[1].rate-limit-per-min", () -> "1000");
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private HttpHeaders bearer(String key) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setBearerAuth(key);
        return h;
    }

    private static final String OK_BODY = """
            {
              "id": "chatcmpl-1",
              "object": "chat.completion",
              "created": 1700000000,
              "model": "%s",
              "choices": [{
                "index": 0,
                "message": {"role": "assistant", "content": "ok"},
                "finish_reason": "stop"
              }],
              "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2}
            }
            """;

    @Test
    void scopedKeyGets403OnDisallowedModel() {
        HttpStatusCodeException ex = catchThrowableOfType(() ->
                        new RestTemplate().exchange(
                                url("/v1/chat/completions"),
                                HttpMethod.POST,
                                new HttpEntity<>(Map.of(
                                        "model", "gpt-4o",
                                        "messages", List.of(Map.of("role", "user", "content", "hi"))),
                                        bearer("mgw_scoped")),
                                String.class),
                HttpStatusCodeException.class);
        assertThat(ex).isNotNull();
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(ex.getResponseBodyAsString()).contains("MODEL_NOT_ALLOWED");
        // Upstream MUST NOT be called when the guard rejects.
        upstream.verify(0, com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor(urlEqualTo("/chat/completions")));
    }

    @Test
    void scopedKeySucceedsOnAllowedModel() {
        upstream.stubFor(post(urlEqualTo("/chat/completions"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(String.format(OK_BODY, "gpt-4o-mini"))));

        ResponseEntity<String> resp = new RestTemplate().exchange(
                url("/v1/chat/completions"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "model", "gpt-4o-mini",
                        "messages", List.of(Map.of("role", "user", "content", "hi"))),
                        bearer("mgw_scoped")),
                String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void unscopedKeyCanHitAnyModel() {
        upstream.stubFor(post(urlEqualTo("/chat/completions"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(String.format(OK_BODY, "gpt-4o"))));

        ResponseEntity<String> resp = new RestTemplate().exchange(
                url("/v1/chat/completions"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "model", "gpt-4o",
                        "messages", List.of(Map.of("role", "user", "content", "hi"))),
                        bearer("mgw_unscoped")),
                String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void modelsEndpointFiltersToAllowedSet() {
        ResponseEntity<String> resp = new RestTemplate().exchange(
                url("/v1/models"),
                HttpMethod.GET,
                new HttpEntity<>(bearer("mgw_scoped")),
                String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).contains("gpt-4o-mini");
        assertThat(resp.getBody()).doesNotContain("\"gpt-4o\"");
    }

    @Test
    void modelsEndpointShowsAllForUnscopedKey() {
        ResponseEntity<String> resp = new RestTemplate().exchange(
                url("/v1/models"),
                HttpMethod.GET,
                new HttpEntity<>(bearer("mgw_unscoped")),
                String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).contains("gpt-4o-mini");
        assertThat(resp.getBody()).contains("\"gpt-4o\"");
    }
}
