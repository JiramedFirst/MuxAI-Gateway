package com.muxai.gateway.provider.openai;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.muxai.gateway.config.ProviderProperties;
import com.muxai.gateway.provider.ProviderException;
import com.muxai.gateway.provider.model.ChatMessage;
import com.muxai.gateway.provider.model.ChatRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

class OpenAiProviderTest {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance()
            .options(com.github.tomakehurst.wiremock.core.WireMockConfiguration.options()
                    .dynamicPort())
            .build();

    private OpenAiProvider newProvider(long timeoutMs) {
        ProviderProperties props = new ProviderProperties(
                "openai-test",
                "openai",
                wm.baseUrl(),
                "sk-test-key",
                timeoutMs,
                List.of("gpt-4o"));
        OpenAiProviderFactory factory = new OpenAiProviderFactory(WebClient.builder());
        return factory.create(props);
    }

    private ChatRequest sampleRequest() {
        return new ChatRequest(
                "gpt-4o",
                List.of(new ChatMessage("user", "hi")),
                0.5, null, 100, null, null);
    }

    @Test
    void happyPathReturnsChatResponse() {
        wm.stubFor(post(urlEqualTo("/chat/completions"))
                .withHeader("Authorization", equalTo("Bearer sk-test-key"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "id": "chatcmpl-abc",
                                  "object": "chat.completion",
                                  "created": 1700000000,
                                  "model": "gpt-4o",
                                  "choices": [{
                                    "index": 0,
                                    "message": {"role": "assistant", "content": "hello!"},
                                    "finish_reason": "stop"
                                  }],
                                  "usage": {"prompt_tokens": 5, "completion_tokens": 2, "total_tokens": 7}
                                }
                                """)));

        OpenAiProvider provider = newProvider(60_000L);
        StepVerifier.create(provider.chat(sampleRequest()))
                .assertNext(resp -> {
                    assertThat(resp.id()).isEqualTo("chatcmpl-abc");
                    assertThat(resp.choices()).hasSize(1);
                    assertThat(resp.choices().get(0).message().content()).isEqualTo("hello!");
                    assertThat(resp.choices().get(0).finishReason()).isEqualTo("stop");
                    assertThat(resp.usage().promptTokens()).isEqualTo(5);
                    assertThat(resp.usage().completionTokens()).isEqualTo(2);
                    assertThat(resp.usage().totalTokens()).isEqualTo(7);
                })
                .verifyComplete();
    }

    @Test
    void unauthorizedMapsToAuthFailed() {
        wm.stubFor(post(urlEqualTo("/chat/completions"))
                .willReturn(aResponse().withStatus(401).withBody("unauthorized")));

        OpenAiProvider provider = newProvider(60_000L);
        StepVerifier.create(provider.chat(sampleRequest()))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(ProviderException.class);
                    assertThat(((ProviderException) err).code())
                            .isEqualTo(ProviderException.Code.AUTH_FAILED);
                })
                .verify();
    }

    @Test
    void tooManyRequestsMapsToRateLimited() {
        wm.stubFor(post(urlEqualTo("/chat/completions"))
                .willReturn(aResponse().withStatus(429).withBody("slow down")));

        OpenAiProvider provider = newProvider(60_000L);
        StepVerifier.create(provider.chat(sampleRequest()))
                .expectErrorSatisfies(err -> {
                    ProviderException pe = (ProviderException) err;
                    assertThat(pe.code()).isEqualTo(ProviderException.Code.RATE_LIMITED);
                    assertThat(pe.code().retryable).isTrue();
                })
                .verify();
    }

    @Test
    void serverErrorMapsToProviderError() {
        wm.stubFor(post(urlEqualTo("/chat/completions"))
                .willReturn(aResponse().withStatus(502).withBody("bad upstream")));

        OpenAiProvider provider = newProvider(60_000L);
        StepVerifier.create(provider.chat(sampleRequest()))
                .expectErrorSatisfies(err -> {
                    ProviderException pe = (ProviderException) err;
                    assertThat(pe.code()).isEqualTo(ProviderException.Code.PROVIDER_ERROR);
                    assertThat(pe.code().retryable).isTrue();
                })
                .verify();
    }

    @Test
    void timeoutMapsToTimeout() {
        wm.stubFor(post(urlEqualTo("/chat/completions"))
                .willReturn(aResponse().withStatus(200).withFixedDelay(500)
                        .withBody("{}")));

        OpenAiProvider provider = newProvider(50L);
        StepVerifier.create(provider.chat(sampleRequest()))
                .expectErrorSatisfies(err -> {
                    ProviderException pe = (ProviderException) err;
                    assertThat(pe.code()).isEqualTo(ProviderException.Code.TIMEOUT);
                })
                .verify();
    }

    @Test
    void missingApiKeyFailsImmediately() {
        ProviderProperties props = new ProviderProperties(
                "openai-test", "openai", wm.baseUrl(), "", 60_000L, List.of("gpt-4o"));
        OpenAiProviderFactory factory = new OpenAiProviderFactory(WebClient.builder());
        OpenAiProvider provider = factory.create(props);

        StepVerifier.create(provider.chat(sampleRequest()))
                .expectErrorSatisfies(err -> {
                    ProviderException pe = (ProviderException) err;
                    assertThat(pe.code()).isEqualTo(ProviderException.Code.AUTH_FAILED);
                })
                .verify();
    }
}
