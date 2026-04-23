package com.muxai.gateway.pii;

import com.muxai.gateway.observability.RequestMetrics;
import com.muxai.gateway.provider.model.ChatMessage;
import com.muxai.gateway.provider.model.ChatRequest;
import com.muxai.gateway.provider.model.ChatResponse;
import com.muxai.gateway.provider.model.ContentPart;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Redacts common PII from chat messages before forwarding to any provider.
 *
 * The redactor is intentionally simple: regex-based, applied to text only,
 * and structurally opaque (it replaces matched substrings with markers like
 * {@code [REDACTED_EMAIL]}). More sophisticated detection (named-entity
 * recognition, contextual redaction) belongs in a plug-in point, not a
 * general-purpose gateway hot path.
 *
 * Credit-card detection applies a Luhn check on top of the regex so that
 * coincidental 16-digit runs (timestamps, order numbers) don't trip it.
 */
@Component
public class PiiRedactor {

    private static final Logger log = LoggerFactory.getLogger(PiiRedactor.class);

    private static final Pattern EMAIL = Pattern.compile(
            "[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern PHONE = Pattern.compile(
            "(?<!\\d)(?:\\+?\\d{1,3}[\\s-]?)?(?:\\(?\\d{2,4}\\)?[\\s-]?)?\\d{3,4}[\\s-]?\\d{3,4}(?!\\d)");
    private static final Pattern CREDIT_CARD = Pattern.compile(
            "(?<!\\d)(?:\\d[\\s-]?){13,19}(?!\\d)");
    private static final Pattern SSN = Pattern.compile(
            "(?<!\\d)\\d{3}-\\d{2}-\\d{4}(?!\\d)");
    private static final Pattern IPV4 = Pattern.compile(
            "(?<!\\d)(?:\\d{1,3}\\.){3}\\d{1,3}(?!\\d)");

    private final PiiProperties props;
    private final RequestMetrics metrics;

    public PiiRedactor(PiiProperties props, RequestMetrics metrics) {
        this.props = props;
        this.metrics = metrics;
    }

    public boolean enabled() { return props.enabledOrDefault(); }

    public boolean outboundEnabled() { return props.outboundEnabled(); }

    public ChatRequest redact(ChatRequest request) {
        if (!props.enabledOrDefault() || request.messages() == null) return request;

        List<ChatMessage> redacted = new ArrayList<>(request.messages().size());
        boolean changed = false;
        for (ChatMessage m : request.messages()) {
            ChatMessage r = redactMessage(m);
            if (r != m) changed = true;
            redacted.add(r);
        }
        return changed ? request.withMessages(redacted) : request;
    }

    /**
     * Scrub PII from a blocking chat response. Streaming responses are NOT
     * handled here — patterns can span chunk boundaries (a credit card
     * split across two SSE events would slip past per-chunk regex), so the
     * sliding-window design that handles streaming lands in a later sprint.
     *
     * No-op when {@link PiiProperties#outboundEnabled()} is false (the default).
     */
    public ChatResponse redactResponse(ChatResponse response) {
        if (!props.outboundEnabled() || response == null || response.choices() == null) return response;
        List<ChatResponse.Choice> redacted = new ArrayList<>(response.choices().size());
        boolean changed = false;
        for (ChatResponse.Choice c : response.choices()) {
            ChatResponse.Choice r = redactChoice(c);
            if (r != c) changed = true;
            redacted.add(r);
        }
        if (!changed) return response;
        return new ChatResponse(response.id(), response.object(), response.created(),
                response.model(), redacted, response.usage());
    }

    private ChatResponse.Choice redactChoice(ChatResponse.Choice c) {
        if (c == null || c.message() == null) return c;
        ChatMessage redactedMsg = redactMessage(c.message());
        if (redactedMsg == c.message()) return c;
        return new ChatResponse.Choice(c.index(), redactedMsg, c.finishReason());
    }

    public String redactText(String text) {
        if (text == null) return null;
        Counts counts = new Counts();
        String out = applyAll(text, counts);
        reportCounts(counts);
        return out;
    }

    private ChatMessage redactMessage(ChatMessage m) {
        if (m == null) return null;
        Object content = m.content();
        if (content instanceof String s) {
            String redacted = applyAll(s, null);
            if (redacted.equals(s)) return m;
            return new ChatMessage(m.role(), redacted, m.toolCalls(), m.toolCallId(), m.name());
        }
        if (content instanceof List<?> list) {
            List<Object> parts = new ArrayList<>(list.size());
            boolean changed = false;
            for (Object item : list) {
                Object r = redactPart(item);
                if (r != item) changed = true;
                parts.add(r);
            }
            if (!changed) return m;
            return new ChatMessage(m.role(), parts, m.toolCalls(), m.toolCallId(), m.name());
        }
        return m;
    }

    private Object redactPart(Object part) {
        if (part instanceof Map<?, ?> map && "text".equals(map.get("type"))) {
            Object text = map.get("text");
            if (text instanceof String s) {
                String redacted = applyAll(s, null);
                if (!redacted.equals(s)) {
                    Map<String, Object> copy = new LinkedHashMap<>();
                    for (Map.Entry<?, ?> e : map.entrySet()) {
                        copy.put(String.valueOf(e.getKey()), e.getValue());
                    }
                    copy.put("text", redacted);
                    return copy;
                }
            }
            return part;
        }
        if (part instanceof ContentPart cp && "text".equals(cp.type()) && cp.text() != null) {
            String redacted = applyAll(cp.text(), null);
            if (redacted.equals(cp.text())) return part;
            return new ContentPart("text", redacted, null);
        }
        return part;
    }

    private String applyAll(String input, Counts countsAccum) {
        Counts c = countsAccum != null ? countsAccum : new Counts();
        String s = input;
        if (props.creditCardEnabled()) {
            s = replaceCreditCards(s, c);
        }
        if (props.ssnEnabled()) {
            s = replaceCount(s, SSN, "[REDACTED_SSN]", "ssn", c);
        }
        if (props.emailEnabled()) {
            s = replaceCount(s, EMAIL, "[REDACTED_EMAIL]", "email", c);
        }
        if (props.phoneEnabled()) {
            s = replaceCount(s, PHONE, "[REDACTED_PHONE]", "phone", c);
        }
        if (props.ipv4Enabled()) {
            s = replaceCount(s, IPV4, "[REDACTED_IP]", "ipv4", c);
        }
        if (countsAccum == null) reportCounts(c);
        return s;
    }

    private static String replaceCount(String s, Pattern p, String replacement, String kind, Counts c) {
        java.util.regex.Matcher m = p.matcher(s);
        StringBuilder sb = new StringBuilder(s.length());
        int last = 0;
        int n = 0;
        while (m.find()) {
            sb.append(s, last, m.start()).append(replacement);
            last = m.end();
            n++;
        }
        if (n == 0) return s;
        sb.append(s, last, s.length());
        c.add(kind, n);
        return sb.toString();
    }

    private static String replaceCreditCards(String s, Counts c) {
        java.util.regex.Matcher m = CREDIT_CARD.matcher(s);
        StringBuilder sb = new StringBuilder(s.length());
        int last = 0;
        int hits = 0;
        while (m.find()) {
            String digits = m.group().replaceAll("\\D", "");
            if (digits.length() >= 13 && digits.length() <= 19 && luhnValid(digits)) {
                sb.append(s, last, m.start()).append("[REDACTED_CARD]");
                last = m.end();
                hits++;
            }
        }
        if (hits == 0) return s;
        sb.append(s, last, s.length());
        c.add("credit_card", hits);
        return sb.toString();
    }

    static boolean luhnValid(String digits) {
        int sum = 0;
        boolean alt = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int d = digits.charAt(i) - '0';
            if (alt) {
                d *= 2;
                if (d > 9) d -= 9;
            }
            sum += d;
            alt = !alt;
        }
        return sum % 10 == 0;
    }

    private void reportCounts(Counts c) {
        if (c.total == 0) return;
        String appId = resolveAppId();
        for (Map.Entry<String, Integer> e : c.byKind.entrySet()) {
            for (int i = 0; i < e.getValue(); i++) {
                metrics.recordPiiRedaction(appId, e.getKey());
            }
        }
        log.debug("pii_redacted total={} app_id={} by_kind={}", c.total, appId, c.byKind);
    }

    private static String resolveAppId() {
        try {
            var auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.getPrincipal() instanceof com.muxai.gateway.auth.AppPrincipal p) {
                return p.appId();
            }
        } catch (Exception ignored) { /* context may be cleared */ }
        return "unknown";
    }

    private static final class Counts {
        int total = 0;
        final Map<String, Integer> byKind = new LinkedHashMap<>();
        void add(String kind, int n) { byKind.merge(kind, n, Integer::sum); total += n; }
    }
}
