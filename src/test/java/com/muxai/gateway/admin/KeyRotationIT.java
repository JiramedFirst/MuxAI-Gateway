package com.muxai.gateway.admin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * End-to-end test for POST /admin/api/keys/rotate.
 *
 * The grace window is shrunk to 2 s so the test can prove the old key
 * actually stops working after expiry without sleeping for 10 minutes.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "spring.config.import=optional:classpath:/non-existent-test.yml",
        "muxai.admin.rotation-grace-seconds=2"
})
class KeyRotationIT {

    @TempDir
    static Path tmp;

    @LocalServerPort
    int port;

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry r) {
        r.add("muxai.admin.runtime-keys-path",
                () -> tmp.resolve("runtime-keys.yml").toString());

        r.add("muxai.providers[0].id", () -> "p1");
        r.add("muxai.providers[0].type", () -> "openai");
        r.add("muxai.providers[0].base-url", () -> "https://example.com/v1");
        r.add("muxai.providers[0].api-key", () -> "sk-x");
        r.add("muxai.providers[0].models[0]", () -> "gpt-4o");

        r.add("muxai.routes[0].primary.provider", () -> "p1");

        r.add("muxai.api-keys[0].key", () -> "mgw_admin_token");
        r.add("muxai.api-keys[0].app-id", () -> "ops");
        r.add("muxai.api-keys[0].role", () -> "admin");

        // Target of the rotation — an app key.
        r.add("muxai.api-keys[1].key", () -> "mgw_original");
        r.add("muxai.api-keys[1].app-id", () -> "target-app");
        r.add("muxai.api-keys[1].rate-limit-per-min", () -> "1000");
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private HttpHeaders bearerJson(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setBearerAuth(token);
        return h;
    }

    @Test
    @SuppressWarnings("unchecked")
    void happyPathRotatesKeyOldStaysValidDuringGrace() {
        // 1. Rotate
        ResponseEntity<Map> resp = new RestTemplate().exchange(
                url("/admin/api/keys/rotate"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("key", "mgw_original"),
                        bearerJson("mgw_admin_token")),
                Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);

        Map<String, Object> body = resp.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("appId")).isEqualTo("target-app");
        assertThat(body.get("newKey")).asString().startsWith("mgw_");
        assertThat(body.get("graceSeconds")).isEqualTo(2);
        assertThat(body.get("oldKeyExpiresAt")).isNotNull();

        String newKey = (String) body.get("newKey");

        // 2. Both keys work right now.
        HttpStatusCodeException okOld = catchThrowableOfType(() ->
                        new RestTemplate().exchange(url("/v1/models"), HttpMethod.GET,
                                new HttpEntity<>(bearerJson("mgw_original")), String.class),
                HttpStatusCodeException.class);
        // /v1/models should return 200 for the old key — no exception.
        assertThat(okOld).isNull();

        ResponseEntity<String> newOk = new RestTemplate().exchange(
                url("/v1/models"), HttpMethod.GET,
                new HttpEntity<>(bearerJson(newKey)), String.class);
        assertThat(newOk.getStatusCode()).isEqualTo(HttpStatus.OK);

        // 3. After grace, old key is 401; new key still works.
        HttpStatusCodeException expired = null;
        long deadline = System.currentTimeMillis() + 5_000L;
        while (System.currentTimeMillis() < deadline) {
            expired = catchThrowableOfType(() ->
                            new RestTemplate().exchange(url("/v1/models"), HttpMethod.GET,
                                    new HttpEntity<>(bearerJson("mgw_original")), String.class),
                    HttpStatusCodeException.class);
            if (expired != null && expired.getStatusCode() == HttpStatus.UNAUTHORIZED) break;
            try {
                Thread.sleep(300);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        assertThat(expired).as("old key should 401 after grace").isNotNull();
        assertThat(expired.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        ResponseEntity<String> stillOk = new RestTemplate().exchange(
                url("/v1/models"), HttpMethod.GET,
                new HttpEntity<>(bearerJson(newKey)), String.class);
        assertThat(stillOk.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void rotateRequiresAdminRole() {
        // Try rotation with a non-admin token (the target key itself).
        HttpStatusCodeException ex = catchThrowableOfType(() ->
                        new RestTemplate().exchange(
                                url("/admin/api/keys/rotate"),
                                HttpMethod.POST,
                                new HttpEntity<>(Map.of("key", "mgw_original"),
                                        bearerJson("mgw_original")),
                                String.class),
                HttpStatusCodeException.class);
        assertThat(ex).isNotNull();
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void rotateNonExistentKeyReturns400() {
        HttpStatusCodeException ex = catchThrowableOfType(() ->
                        new RestTemplate().exchange(
                                url("/admin/api/keys/rotate"),
                                HttpMethod.POST,
                                new HttpEntity<>(Map.of("key", "mgw_nope"),
                                        bearerJson("mgw_admin_token")),
                                String.class),
                HttpStatusCodeException.class);
        assertThat(ex).isNotNull();
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ex.getResponseBodyAsString()).contains("No active key");
    }
}
