package com.muxai.gateway.provider.anthropic;

import com.muxai.gateway.config.ProviderProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

@Component
public class AnthropicProviderFactory {

    private final WebClient.Builder builder;

    public AnthropicProviderFactory(WebClient.Builder builder) {
        this.builder = builder;
    }

    public AnthropicProvider create(ProviderProperties props) {
        WebClient.Builder local = builder.clone()
                .baseUrl(props.baseUrl())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader("anthropic-version", "2023-06-01");
        if (props.apiKey() != null && !props.apiKey().isBlank()) {
            local = local.defaultHeader("x-api-key", props.apiKey());
        }
        return new AnthropicProvider(props, local.build());
    }
}
