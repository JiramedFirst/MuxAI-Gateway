package com.muxai.gateway.ratelimit;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import java.net.HttpURLConnection;
import java.net.URI;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "spring.config.import=optional:classpath:/non-existent-test.yml"
})
class RateLimitIT {

    static WireMockServer upstream;

    @LocalServerPort
    int port;

    @BeforeAll
    static void start() {
        upstream = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        upstream.start();
    }

    @AfterAll
    static void stop() {
        upstream.stop();
    }

    @AfterEach
    void reset() {
        upstream.resetAll();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("muxai.providers[0].id", () -> "primary");
        r.add("muxai.providers[0].type", () -> "openai");
        r.add("muxai.providers[0].base-url", upstream::baseUrl);
        r.add("muxai.providers[0].api-key", () -> "sk-test");
        r.add("muxai.providers[0].timeout-ms", () -> "5000");
        r.add("muxai.providers[0].models[0]", () -> "gpt-4o");

        r.add("muxai.routes[0].primary.provider", () -> "primary");

        // Each test uses its own app-id so buckets don't cross-contaminate
        // when the Spring context (and hence RateLimiter) is shared.
        r.add("muxai.api-keys[0].key", () -> "mgw_rate_under");
        r.add("muxai.api-keys[0].app-id", () -> "rate-app-under");
        r.add("muxai.api-keys[0].rate-limit-per-min", () -> "2");

        r.add("muxai.api-keys[1].key", () -> "mgw_rate_over");
        r.add("muxai.api-keys[1].app-id", () -> "rate-app-over");
        r.add("muxai.api-keys[1].rate-limit-per-min", () -> "2");

        r.add("muxai.api-keys[2].key", () -> "mgw_unlimited");
        r.add("muxai.api-keys[2].app-id", () -> "unlimited-app");
        // no rate-limit-per-min -> unlimited

        r.add("muxai.api-keys[3].key", () -> "mgw_public_key");
        r.add("muxai.api-keys[3].app-id", () -> "public-app");
        r.add("muxai.api-keys[3].rate-limit-per-min", () -> "1");
    }

    private static final String OK_BODY = """
            {"id":"x","object":"chat.completion","created":1,"model":"gpt-4o",
             "choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}],
             "usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}
            """;

    private HttpURLConnection doPost(String path, String apiKey) throws Exception {
        HttpURLConnection conn = (HttpURLConnection)
                URI.create("http://localhost:" + port + path).toURL().openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        conn.setDoOutput(true);
        conn.getOutputStream().write(
                "{\"model\":\"gpt-4o\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}".getBytes());
        conn.getResponseCode();
        return conn;
    }

    private HttpURLConnection doGet(String path, String apiKey) throws Exception {
        HttpURLConnection conn = (HttpURLConnection)
                URI.create("http://localhost:" + port + path).toURL().openConnection();
        conn.setRequestMethod("GET");
        if (apiKey != null) {
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        }
        conn.getResponseCode();
        return conn;
    }

    private static String readBody(HttpURLConnection conn) throws Exception {
        try (var is = conn.getResponseCode() >= 400 ? conn.getErrorStream() : conn.getInputStream()) {
            return is != null ? new String(is.readAllBytes()) : "";
        }
    }

    @Test
    void underQuotaSucceedsAndExposesRateLimitHeaders() throws Exception {
        upstream.stubFor(post(urlEqualTo("/chat/completions"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(OK_BODY)));

        HttpURLConnection conn = doPost("/v1/chat/completions", "mgw_rate_under");
        assertThat(conn.getResponseCode()).isEqualTo(200);
        assertThat(conn.getHeaderField("X-RateLimit-Limit")).isEqualTo("2");
        // First call for this app-id consumes 1 of 2 tokens.
        assertThat(conn.getHeaderField("X-RateLimit-Remaining")).isEqualTo("1");
    }

    @Test
    void exceedingQuotaReturns429WithRetryAfter() throws Exception {
        upstream.stubFor(post(urlEqualTo("/chat/completions"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(OK_BODY)));

        // Dedicated app-id with limit=2 — drain the bucket then expect 429.
        doPost("/v1/chat/completions", "mgw_rate_over");
        doPost("/v1/chat/completions", "mgw_rate_over");

        HttpURLConnection conn = doPost("/v1/chat/completions", "mgw_rate_over");
        assertThat(conn.getResponseCode()).isEqualTo(429);
        assertThat(conn.getHeaderField("Retry-After")).isNotNull();
        assertThat(Integer.parseInt(conn.getHeaderField("Retry-After"))).isGreaterThanOrEqualTo(1);
        assertThat(conn.getHeaderField("X-RateLimit-Limit")).isEqualTo("2");
        assertThat(conn.getHeaderField("X-RateLimit-Remaining")).isEqualTo("0");

        String body = readBody(conn);
        assertThat(body).contains("RATE_LIMITED");
        assertThat(body).contains("rate_limit_exceeded");
    }

    @Test
    void unlimitedAppDoesNotEmitRateLimitHeaders() throws Exception {
        upstream.stubFor(post(urlEqualTo("/chat/completions"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(OK_BODY)));

        HttpURLConnection conn = doPost("/v1/chat/completions", "mgw_unlimited");
        assertThat(conn.getResponseCode()).isEqualTo(200);
        assertThat(conn.getHeaderField("X-RateLimit-Limit")).isNull();
        assertThat(conn.getHeaderField("X-RateLimit-Remaining")).isNull();
        assertThat(conn.getHeaderField("Retry-After")).isNull();
    }

    @Test
    void publicPathSkipsRateLimitingEntirely() throws Exception {
        // /actuator/health is on PublicPaths and does not require auth. The filter
        // must shouldNotFilter() this path — otherwise it would either 401 (no
        // principal) or set rate-limit headers. Verify both negatives.
        HttpURLConnection conn = doGet("/actuator/health", null);
        assertThat(conn.getResponseCode()).isEqualTo(200);
        assertThat(conn.getHeaderField("X-RateLimit-Limit")).isNull();
        assertThat(conn.getHeaderField("X-RateLimit-Remaining")).isNull();
    }
}
