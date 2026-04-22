package com.muxai.gateway.provider.openai;

import com.muxai.gateway.config.ProviderProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

@Component
public class OpenAiProviderFactory {

    private final WebClient.Builder builder;

    @Autowired
    public OpenAiProviderFactory(WebClient.Builder builder) {
        this.builder = builder;
    }

    public OpenAiProvider create(ProviderProperties props) {
        WebClient.Builder local = builder.clone()
                .baseUrl(props.baseUrl())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
        if (props.apiKey() != null && !props.apiKey().isBlank()) {
            local = local.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + props.apiKey());
        }
        return new OpenAiProvider(props, local.build());
    }
}
