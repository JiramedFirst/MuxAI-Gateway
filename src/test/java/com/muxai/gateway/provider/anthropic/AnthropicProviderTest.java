package com.muxai.gateway.provider.anthropic;

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
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

class AnthropicProviderTest {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance()
            .options(com.github.tomakehurst.wiremock.core.WireMockConfiguration.options()
                    .dynamicPort())
            .build();

    private AnthropicProvider newProvider() {
        ProviderProperties props = new ProviderProperties(
                "anthropic-test",
                "anthropic",
                wm.baseUrl(),
                "ant-test-key",
                60_000L,
                List.of("claude-sonnet-4-6"));
        AnthropicProviderFactory factory = new AnthropicProviderFactory(WebClient.builder());
        return factory.create(props);
    }

    @Test
    void translatesResponseBackToOpenAiShape() {
        wm.stubFor(post(urlEqualTo("/messages"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "id": "msg_abc",
                                  "type": "message",
                                  "role": "assistant",
                                  "model": "claude-sonnet-4-6",
                                  "content": [{"type": "text", "text": "Hello back!"}],
                                  "stop_reason": "end_turn",
                                  "usage": {"input_tokens": 12, "output_tokens": 4}
                                }
                                """)));

        AnthropicProvider provider = newProvider();
        ChatRequest request = new ChatRequest(
                "claude-sonnet-4-6",
                List.of(
                        new ChatMessage("system", "Be terse."),
                        new ChatMessage("user", "hi")),
                0.4, 0.9, null, null, null);

        StepVerifier.create(provider.chat(request))
                .assertNext(resp -> {
                    assertThat(resp.object()).isEqualTo("chat.completion");
                    assertThat(resp.id()).isEqualTo("msg_abc");
                    assertThat(resp.choices()).hasSize(1);
                    assertThat(resp.choices().get(0).message().role()).isEqualTo("assistant");
                    assertThat(resp.choices().get(0).message().content()).isEqualTo("Hello back!");
                    assertThat(resp.choices().get(0).finishReason()).isEqualTo("stop");
                    assertThat(resp.usage().promptTokens()).isEqualTo(12);
                    assertThat(resp.usage().completionTokens()).isEqualTo(4);
                    assertThat(resp.usage().totalTokens()).isEqualTo(16);
                })
                .verifyComplete();

        wm.verify(postRequestedFor(urlEqualTo("/messages"))
                .withHeader("x-api-key", equalTo("ant-test-key"))
                .withHeader("anthropic-version", equalTo("2023-06-01"))
                .withRequestBody(matchingJsonPath("$.system", equalTo("Be terse.")))
                .withRequestBody(matchingJsonPath("$.max_tokens", equalTo("4096")))
                .withRequestBody(matchingJsonPath("$.messages[0].role", equalTo("user")))
                .withRequestBody(matchingJsonPath("$.messages[0].content", equalTo("hi"))));
    }

    @Test
    void doesNotSendAuthorizationHeader() {
        wm.stubFor(post(urlEqualTo("/messages"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                        {
                          "id": "msg_x",
                          "type": "message",
                          "role": "assistant",
                          "model": "claude-sonnet-4-6",
                          "content": [{"type": "text", "text": ""}],
                          "stop_reason": "end_turn",
                          "usage": {"input_tokens": 0, "output_tokens": 0}
                        }""")));

        AnthropicProvider provider = newProvider();
        ChatRequest req = new ChatRequest(
                "claude-sonnet-4-6",
                List.of(new ChatMessage("user", "hi")),
                null, null, null, null, null);

        StepVerifier.create(provider.chat(req)).expectNextCount(1).verifyComplete();

        wm.verify(postRequestedFor(urlEqualTo("/messages"))
                .withoutHeader("Authorization"));
    }

    @Test
    void mapsStopReasonMaxTokensToLength() {
        wm.stubFor(post(urlEqualTo("/messages"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                        {
                          "id": "msg_1",
                          "type": "message",
                          "role": "assistant",
                          "model": "claude-sonnet-4-6",
                          "content": [{"type": "text", "text": "..."}],
                          "stop_reason": "max_tokens",
                          "usage": {"input_tokens": 1, "output_tokens": 1}
                        }""")));
        AnthropicProvider provider = newProvider();

        StepVerifier.create(provider.chat(new ChatRequest(
                        "claude-sonnet-4-6",
                        List.of(new ChatMessage("user", "x")),
                        null, null, null, null, null)))
                .assertNext(resp -> assertThat(resp.choices().get(0).finishReason()).isEqualTo("length"))
                .verifyComplete();
    }

    @Test
    void unauthorizedMapsToAuthFailed() {
        wm.stubFor(post(urlEqualTo("/messages"))
                .willReturn(aResponse().withStatus(401).withBody("bad key")));

        AnthropicProvider provider = newProvider();
        StepVerifier.create(provider.chat(new ChatRequest(
                        "claude-sonnet-4-6",
                        List.of(new ChatMessage("user", "hi")),
                        null, null, null, null, null)))
                .expectErrorSatisfies(err -> assertThat(((ProviderException) err).code())
                        .isEqualTo(ProviderException.Code.AUTH_FAILED))
                .verify();
    }

    @Test
    void embeddingsUnsupported() {
        AnthropicProvider provider = newProvider();
        assertThat(provider.capabilities().embeddings()).isFalse();
    }

    @Test
    void ocrReturnsInvalidRequestByDefault() {
        AnthropicProvider provider = newProvider();
        StepVerifier.create(provider.ocr(new com.muxai.gateway.provider.model.OcrRequest(
                        "claude-sonnet-4-6", "data:image/png;base64,AA", null, null)))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(ProviderException.class);
                    assertThat(((ProviderException) err).code())
                            .isEqualTo(ProviderException.Code.INVALID_REQUEST);
                })
                .verify();
    }
}
