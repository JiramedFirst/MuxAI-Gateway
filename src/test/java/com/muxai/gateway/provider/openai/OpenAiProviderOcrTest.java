package com.muxai.gateway.provider.openai;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.muxai.gateway.config.ProviderProperties;
import com.muxai.gateway.provider.model.OcrRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

class OpenAiProviderOcrTest {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance()
            .options(com.github.tomakehurst.wiremock.core.WireMockConfiguration.options()
                    .dynamicPort())
            .build();

    private OpenAiProvider newProvider() {
        ProviderProperties props = new ProviderProperties(
                "opentyphoon-test",
                "openai",
                wm.baseUrl(),
                "sk-test-key",
                60_000L,
                List.of("typhoon-ocr"),
                null);
        return new OpenAiProviderFactory(WebClient.builder(), new com.fasterxml.jackson.databind.ObjectMapper()).create(props);
    }

    @Test
    void ocrBuildsMultimodalRequestAndExtractsText() {
        wm.stubFor(post(urlEqualTo("/chat/completions"))
                .withHeader("Authorization", equalTo("Bearer sk-test-key"))
                .withRequestBody(matchingJsonPath("$.model", equalTo("typhoon-ocr")))
                .withRequestBody(matchingJsonPath("$.messages[0].content[0].type", equalTo("text")))
                .withRequestBody(matchingJsonPath("$.messages[0].content[1].type", equalTo("image_url")))
                .withRequestBody(matchingJsonPath(
                        "$.messages[0].content[1].image_url.url",
                        equalTo("data:image/png;base64,ABC123")))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "id": "chatcmpl-ocr",
                                  "model": "typhoon-ocr",
                                  "choices": [{
                                    "index": 0,
                                    "message": {"role": "assistant", "content": "# Extracted\\nHello world"},
                                    "finish_reason": "stop"
                                  }],
                                  "usage": {"prompt_tokens": 42, "completion_tokens": 7, "total_tokens": 49}
                                }
                                """)));

        OcrRequest req = new OcrRequest(
                "typhoon-ocr",
                "data:image/png;base64,ABC123",
                null,
                null);

        StepVerifier.create(newProvider().ocr(req))
                .assertNext(resp -> {
                    assertThat(resp.model()).isEqualTo("typhoon-ocr");
                    assertThat(resp.text()).isEqualTo("# Extracted\nHello world");
                    assertThat(resp.usage().totalTokens()).isEqualTo(49);
                })
                .verifyComplete();
    }
}
