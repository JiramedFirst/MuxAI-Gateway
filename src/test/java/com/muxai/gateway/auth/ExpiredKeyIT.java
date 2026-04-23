package com.muxai.gateway.auth;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Verifies that ApiKey.expiresAt is honoured at request time (Sprint 1a).
 *
 * The auth filter rejects expired keys with 401 (same shape as a missing/
 * invalid key) so operators can leave expired entries in providers.yml until
 * their next cleanup pass without breaking startup.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "spring.config.import=optional:classpath:/non-existent-test.yml"
})
class ExpiredKeyIT {

    @LocalServerPort
    int port;

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry r) {
        r.add("muxai.providers[0].id", () -> "p1");
        r.add("muxai.providers[0].type", () -> "openai");
        r.add("muxai.providers[0].base-url", () -> "https://example.com/v1");
        r.add("muxai.providers[0].api-key", () -> "sk-x");
        r.add("muxai.providers[0].models[0]", () -> "gpt-4o");

        r.add("muxai.routes[0].primary.provider", () -> "p1");

        // Expired one minute ago.
        r.add("muxai.api-keys[0].key", () -> "mgw_expired");
        r.add("muxai.api-keys[0].app-id", () -> "expired-app");
        r.add("muxai.api-keys[0].expires-at",
                () -> Instant.now().minusSeconds(60).toString());

        // Non-expired key (no expiresAt) for the control case.
        r.add("muxai.api-keys[1].key", () -> "mgw_live");
        r.add("muxai.api-keys[1].app-id", () -> "live-app");
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private HttpEntity<Void> bearer(String key) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(key);
        return new HttpEntity<>(h);
    }

    @Test
    void expiredKeyReturns401() {
        HttpStatusCodeException ex = catchThrowableOfType(() ->
                        new RestTemplate().exchange(url("/v1/models"),
                                HttpMethod.GET, bearer("mgw_expired"), String.class),
                HttpStatusCodeException.class);
        assertThat(ex).isNotNull();
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(ex.getResponseBodyAsString()).contains("expired");
    }

    @Test
    void liveKeyStillWorks() {
        var resp = new RestTemplate().exchange(url("/v1/models"),
                HttpMethod.GET, bearer("mgw_live"), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
