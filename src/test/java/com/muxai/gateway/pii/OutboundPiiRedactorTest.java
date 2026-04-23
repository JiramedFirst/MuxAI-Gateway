package com.muxai.gateway.pii;

import com.muxai.gateway.observability.RequestMetrics;
import com.muxai.gateway.provider.model.ChatMessage;
import com.muxai.gateway.provider.model.ChatResponse;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OutboundPiiRedactorTest {

    private static PiiRedactor redactor(boolean enabled, boolean outbound) {
        PiiProperties.Outbound o = outbound ? new PiiProperties.Outbound(true) : null;
        PiiProperties props = new PiiProperties(enabled, true, true, true, true, true, o);
        return new PiiRedactor(props, new RequestMetrics(new SimpleMeterRegistry()));
    }

    private static ChatResponse responseWith(String content) {
        return new ChatResponse(
                "chatcmpl-1", "chat.completion", 1700000000L, "gpt-4o",
                List.of(new ChatResponse.Choice(0,
                        new ChatMessage("assistant", content), "stop")),
                null);
    }

    @Test
    void noOpWhenOutboundDisabled() {
        PiiRedactor r = redactor(true, false);
        ChatResponse in = responseWith("contact me at alice@example.com");
        ChatResponse out = r.redactResponse(in);
        assertThat(out).isSameAs(in);
    }

    @Test
    void noOpWhenInboundDisabled() {
        PiiRedactor r = redactor(false, true);
        ChatResponse in = responseWith("alice@example.com");
        assertThat(r.redactResponse(in)).isSameAs(in);
    }

    @Test
    void scrubsEmailFromAssistantMessage() {
        PiiRedactor r = redactor(true, true);
        ChatResponse out = r.redactResponse(responseWith(
                "Reply to alice@example.com when ready"));
        String content = (String) out.choices().get(0).message().content();
        assertThat(content).contains("[REDACTED_EMAIL]");
        assertThat(content).doesNotContain("alice@example.com");
    }

    @Test
    void scrubsCreditCardWithLuhn() {
        PiiRedactor r = redactor(true, true);
        // 4111111111111111 is a known Luhn-valid Visa test number
        ChatResponse out = r.redactResponse(responseWith(
                "Card on file: 4111 1111 1111 1111"));
        String content = (String) out.choices().get(0).message().content();
        assertThat(content).contains("[REDACTED_CARD]");
    }

    @Test
    void preservesNonPiiContentUnchanged() {
        PiiRedactor r = redactor(true, true);
        ChatResponse in = responseWith("The quick brown fox");
        ChatResponse out = r.redactResponse(in);
        // No PII present → identity-equal return so callers can detect no-op.
        assertThat(out).isSameAs(in);
    }

    @Test
    void handlesNullResponseGracefully() {
        PiiRedactor r = redactor(true, true);
        assertThat(r.redactResponse(null)).isNull();
    }
}
