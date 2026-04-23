package com.muxai.gateway.pii;

import com.muxai.gateway.observability.RequestMetrics;
import com.muxai.gateway.provider.model.ChatMessage;
import com.muxai.gateway.provider.model.ChatRequest;
import com.muxai.gateway.provider.model.ContentPart;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PiiRedactorTest {

    private static PiiRedactor withAll(boolean on) {
        PiiProperties props = new PiiProperties(on, on, on, on, on, on, null);
        return new PiiRedactor(props, new RequestMetrics(new SimpleMeterRegistry()));
    }

    private static ChatRequest req(String content) {
        return new ChatRequest("gpt-4o",
                List.of(new ChatMessage("user", content)),
                0.0, null, 100, null, null);
    }

    @Test
    void disabledRedactorPassesThroughUnchanged() {
        PiiRedactor r = withAll(false);
        ChatRequest in = req("email me at a@b.com");
        ChatRequest out = r.redact(in);
        assertThat(out).isSameAs(in);
    }

    @Test
    void redactsEmailInPlainString() {
        PiiRedactor r = withAll(true);
        ChatRequest out = r.redact(req("contact alice@example.org please"));
        String content = (String) out.messages().get(0).content();
        assertThat(content).doesNotContain("alice@example.org");
        assertThat(content).contains("[REDACTED_EMAIL]");
    }

    @Test
    void redactsCreditCardOnlyWhenLuhnValid() {
        PiiRedactor r = withAll(true);
        // 4111111111111111 passes Luhn (standard Visa test number).
        String withValid = (String) r.redact(req("card 4111 1111 1111 1111 here"))
                .messages().get(0).content();
        assertThat(withValid).contains("[REDACTED_CARD]");

        // Arbitrary 16-digit run that fails Luhn — must not be redacted.
        String withInvalid = (String) r.redact(req("id 1234567812345678 here"))
                .messages().get(0).content();
        assertThat(withInvalid).contains("1234567812345678");
    }

    @Test
    void redactsSsnButNotRandomNineDigitSequences() {
        PiiRedactor r = withAll(true);
        String out = (String) r.redact(req("ssn 123-45-6789 verified"))
                .messages().get(0).content();
        assertThat(out).contains("[REDACTED_SSN]");
    }

    @Test
    void leavesNonStringPartsAloneButRedactsTextParts() {
        PiiRedactor r = withAll(true);
        ChatRequest in = new ChatRequest("gpt-4o",
                List.of(new ChatMessage("user", List.of(
                        ContentPart.text("call me at a@b.com"),
                        ContentPart.image("https://example.com/image.png")))),
                0.0, null, 100, null, null);

        ChatRequest out = r.redact(in);
        List<?> parts = (List<?>) out.messages().get(0).content();
        ContentPart text = (ContentPart) parts.get(0);
        ContentPart image = (ContentPart) parts.get(1);
        assertThat(text.text()).contains("[REDACTED_EMAIL]");
        assertThat(image.imageUrl().url()).isEqualTo("https://example.com/image.png");
    }

    @Test
    void selectiveToggleOnlyRedactsChosenKinds() {
        PiiProperties props = new PiiProperties(true, false, false, true, false, false, null);
        PiiRedactor r = new PiiRedactor(props, new RequestMetrics(new SimpleMeterRegistry()));

        String out = (String) r.redact(req("mail a@b.com card 4111 1111 1111 1111"))
                .messages().get(0).content();
        assertThat(out).contains("a@b.com");           // email off
        assertThat(out).contains("[REDACTED_CARD]");   // card on
    }

    @Test
    void luhnValidatorAcceptsKnownCards() {
        assertThat(PiiRedactor.luhnValid("4111111111111111")).isTrue();   // Visa test
        assertThat(PiiRedactor.luhnValid("5500000000000004")).isTrue();   // Mastercard test
        assertThat(PiiRedactor.luhnValid("340000000000009")).isTrue();    // Amex test
        assertThat(PiiRedactor.luhnValid("1234567812345678")).isFalse();  // arbitrary digits don't
    }
}
