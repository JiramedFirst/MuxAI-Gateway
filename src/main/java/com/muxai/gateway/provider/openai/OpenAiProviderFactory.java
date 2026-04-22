package com.muxai.gateway.provider.openai;

import com.muxai.gateway.config.ProviderProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

@Component
public class OpenAiProviderFactory {

    // Large enough to comfortably send a ~27 MB base64 image payload (see OcrApiRequest.MAX_IMAGE_LENGTH).
    static final int MAX_IN_MEMORY_SIZE = 32 * 1024 * 1024;

    private final WebClient.Builder builder;

    @Autowired
    public OpenAiProviderFactory(WebClient.Builder builder) {
        this.builder = builder;
    }

    public OpenAiProvider create(ProviderProperties props) {
        WebClient.Builder local = builder.clone()
                .baseUrl(props.baseUrl())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .codecs(c -> c.defaultCodecs().maxInMemorySize(MAX_IN_MEMORY_SIZE));
        if (props.apiKey() != null && !props.apiKey().isBlank()) {
            local = local.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + props.apiKey());
        }
        return new OpenAiProvider(props, local.build());
    }
}
