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

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "spring.config.import=optional:classpath:/non-existent-test.yml"
})
class ModelsControllerIT {

    // WireMock is not called by /v1/models, but a valid base-url is required
    // by ConfigValidator and ProviderRegistry to construct the WebClient at startup.
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
        r.add("muxai.providers[0].api-key", () -> "sk-p1");
        r.add("muxai.providers[0].timeout-ms", () -> "5000");
        r.add("muxai.providers[0].models[0]", () -> "gpt-4o");
        r.add("muxai.providers[0].models[1]", () -> "gpt-4o-mini");

        r.add("muxai.providers[1].id", () -> "p2");
        r.add("muxai.providers[1].type", () -> "openai");
        r.add("muxai.providers[1].base-url", () -> upstream.baseUrl());
        r.add("muxai.providers[1].api-key", () -> "sk-p2");
        r.add("muxai.providers[1].timeout-ms", () -> "5000");
        r.add("muxai.providers[1].models[0]", () -> "text-embedding-3-small");

        // Catch-all route required by ConfigValidator (routes must not be empty).
        r.add("muxai.routes[0].primary.provider", () -> "p1");

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

    @Test
    void unauthenticatedRequestIs401() throws Exception {
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection)
                java.net.URI.create(url("/v1/models")).toURL().openConnection();
        conn.setRequestMethod("GET");
        assertThat(conn.getResponseCode()).isEqualTo(401);
    }

    @Test
    void authenticatedRequestReturnsModelList() {
        ResponseEntity<String> resp = rest().exchange(
                url("/v1/models"), HttpMethod.GET,
                new HttpEntity<>(authHeaders("mgw_test_devkey")),
                String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).contains("\"object\":\"list\"");
    }

    @Test
    void dataArrayContainsAllConfiguredModels() {
        ResponseEntity<String> resp = rest().exchange(
                url("/v1/models"), HttpMethod.GET,
                new HttpEntity<>(authHeaders("mgw_test_devkey")),
                String.class);
        assertThat(resp.getBody())
                .contains("\"gpt-4o\"")
                .contains("\"gpt-4o-mini\"")
                .contains("\"text-embedding-3-small\"");
    }

    @Test
    void modelsIncludeOwnedByField() {
        ResponseEntity<String> resp = rest().exchange(
                url("/v1/models"), HttpMethod.GET,
                new HttpEntity<>(authHeaders("mgw_test_devkey")),
                String.class);
        assertThat(resp.getBody())
                .contains("\"owned_by\":\"p1\"")
                .contains("\"owned_by\":\"p2\"");
    }

    @Test
    void eachModelHasObjectFieldSetToModel() {
        ResponseEntity<String> resp = rest().exchange(
                url("/v1/models"), HttpMethod.GET,
                new HttpEntity<>(authHeaders("mgw_test_devkey")),
                String.class);
        // ModelInfo.of() always sets object="model"
        assertThat(resp.getBody()).contains("\"object\":\"model\"");
    }
}
