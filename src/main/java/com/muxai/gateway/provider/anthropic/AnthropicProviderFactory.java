package com.muxai.gateway.provider.anthropic;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.muxai.gateway.config.ProviderProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

@Component
public class AnthropicProviderFactory {

    private final WebClient.Builder builder;
    private final ObjectMapper mapper;

    public AnthropicProviderFactory(WebClient.Builder builder, ObjectMapper mapper) {
        this.builder = builder;
        this.mapper = mapper;
    }

    public AnthropicProvider create(ProviderProperties props) {
        WebClient.Builder local = builder.clone()
                .baseUrl(props.baseUrl())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader("anthropic-version", "2023-06-01")
                .defaultHeader("anthropic-beta", "prompt-caching-2024-07-31");
        if (props.apiKey() != null && !props.apiKey().isBlank()) {
            local = local.defaultHeader("x-api-key", props.apiKey());
        }
        return new AnthropicProvider(props, local.build(), mapper);
    }
}
