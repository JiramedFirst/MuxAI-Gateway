package com.muxai.gateway.observability;

import com.muxai.gateway.provider.LlmProvider;
import com.muxai.gateway.provider.ProviderCapabilities;
import com.muxai.gateway.provider.ProviderRegistry;
import com.muxai.gateway.provider.model.ChatRequest;
import com.muxai.gateway.provider.model.ChatResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import reactor.core.publisher.Mono;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ProvidersHealthIndicatorTest {

    @Test
    void reportsDownWhenNoProvidersRegistered() {
        ProviderRegistry.Lookup empty = new ProviderRegistry.Lookup(Map.of());
        Health h = new ProvidersHealthIndicator(empty).health();
        assertThat(h.getStatus()).isEqualTo(Status.DOWN);
        assertThat(h.getDetails()).containsEntry("count", 0);
    }

    @Test
    void reportsUpAndLeaksOnlyIdAndType() {
        LlmProvider p = stubProvider("openai-main", "openai");
        ProviderRegistry.Lookup lookup = new ProviderRegistry.Lookup(Map.of(p.id(), p));

        Health h = new ProvidersHealthIndicator(lookup).health();

        assertThat(h.getStatus()).isEqualTo(Status.UP);
        assertThat(h.getDetails()).containsEntry("count", 1);
        @SuppressWarnings("unchecked")
        Map<String, String> byId = (Map<String, String>) h.getDetails().get("providers");
        assertThat(byId).containsEntry("openai-main", "openai");
        // Sanity: details must not expose base URLs or API keys.
        assertThat(h.getDetails().toString()).doesNotContain("api");
        assertThat(h.getDetails().toString()).doesNotContain("http");
    }

    private static LlmProvider stubProvider(String id, String type) {
        return new LlmProvider() {
            @Override public String id() { return id; }
            @Override public String type() { return type; }
            @Override public boolean supports(String model) { return true; }
            @Override public ProviderCapabilities capabilities() {
                return ProviderCapabilities.chatOnly();
            }
            @Override public Mono<ChatResponse> chat(ChatRequest request) {
                return Mono.empty();
            }
        };
    }
}
