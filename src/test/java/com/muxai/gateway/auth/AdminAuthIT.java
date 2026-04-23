package com.muxai.gateway.auth;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Verifies admin-role gating on /admin/api/** introduced in Sprint 1a.
 *
 * Static admin assets (/admin/index.html, etc.) stay public — only the REST
 * surface requires ROLE_ADMIN. App-role keys (the existing default) get 403,
 * not the previous "any-bearer 200".
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "spring.config.import=optional:classpath:/non-existent-test.yml"
})
class AdminAuthIT {

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

        // Default-role app key (no role => "app").
        r.add("muxai.api-keys[0].key", () -> "mgw_app_key");
        r.add("muxai.api-keys[0].app-id", () -> "appA");
        r.add("muxai.api-keys[0].rate-limit-per-min", () -> "1000");

        // Admin key.
        r.add("muxai.api-keys[1].key", () -> "mgw_admin_key");
        r.add("muxai.api-keys[1].app-id", () -> "ops");
        r.add("muxai.api-keys[1].role", () -> "admin");
        r.add("muxai.api-keys[1].rate-limit-per-min", () -> "1000");
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private HttpEntity<Void> bearer(String key) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        if (key != null) h.setBearerAuth(key);
        return new HttpEntity<>(h);
    }

    @Test
    void unauthenticatedAdminCallReturns401() {
        HttpStatusCodeException ex = catchThrowableOfType(() ->
                        new RestTemplate().exchange(url("/admin/api/overview"),
                                HttpMethod.GET, bearer(null), String.class),
                HttpStatusCodeException.class);
        assertThat(ex).isNotNull();
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void appRoleKeyReturns403OnAdminEndpoint() {
        HttpStatusCodeException ex = catchThrowableOfType(() ->
                        new RestTemplate().exchange(url("/admin/api/overview"),
                                HttpMethod.GET, bearer("mgw_app_key"), String.class),
                HttpStatusCodeException.class);
        assertThat(ex).isNotNull();
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void adminRoleKeyReturns200OnAdminEndpoint() {
        ResponseEntity<String> resp = new RestTemplate().exchange(
                url("/admin/api/overview"), HttpMethod.GET, bearer("mgw_admin_key"), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).contains("\"providers\"");
        assertThat(resp.getBody()).contains("\"apiKeys\"");
        // Surfaced role field shows up for admin keys.
        assertThat(resp.getBody()).contains("\"role\":\"admin\"");
    }
}
