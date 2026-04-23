package com.muxai.gateway.pii;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Inbound PII redaction. When enabled, {@link PiiRedactor} rewrites
 * user-supplied text in chat messages before the request is routed to any
 * provider. Each toggle is independent so operators can redact, say, credit
 * card numbers while leaving emails alone.
 *
 * <p>Replacement tokens are deliberately structured ({@code [REDACTED_EMAIL]}
 * etc.) so the model can still reason about the <i>shape</i> of the original
 * input without seeing the secret. The replacement format is not configurable
 * — keeping it fixed lets downstream tooling grep for redaction markers.
 */
@ConfigurationProperties(prefix = "muxai.pii")
public record PiiProperties(
        Boolean enabled,
        Boolean email,
        Boolean phone,
        Boolean creditCard,
        Boolean ssn,
        Boolean ipv4,
        Outbound outbound
) {
    public boolean enabledOrDefault() { return Boolean.TRUE.equals(enabled); }
    public boolean emailEnabled() { return enabledOrDefault() && !Boolean.FALSE.equals(email); }
    public boolean phoneEnabled() { return enabledOrDefault() && !Boolean.FALSE.equals(phone); }
    public boolean creditCardEnabled() { return enabledOrDefault() && !Boolean.FALSE.equals(creditCard); }
    public boolean ssnEnabled() { return enabledOrDefault() && !Boolean.FALSE.equals(ssn); }
    public boolean ipv4Enabled() { return enabledOrDefault() && !Boolean.FALSE.equals(ipv4); }

    /**
     * Outbound (response) scrubbing currently runs on blocking chat responses
     * only. Streaming responses are NOT scrubbed yet — patterns split across
     * SSE chunk boundaries defeat the regex without a sliding-window design.
     * Defaults to disabled even when {@link #enabled} is true so existing
     * deployments that opt into inbound PII don't suddenly start mutating
     * provider responses.
     */
    public boolean outboundEnabled() {
        return enabledOrDefault() && outbound != null && Boolean.TRUE.equals(outbound.enabled());
    }

    public record Outbound(Boolean enabled) {}
}
