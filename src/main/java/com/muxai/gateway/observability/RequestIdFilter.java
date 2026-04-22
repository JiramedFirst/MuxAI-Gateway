package com.muxai.gateway.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Stamps every HTTP request with a correlation id so logs, metrics, and
 * upstream-propagated headers can be stitched together after the fact.
 *
 * The id is taken from the inbound {@code X-Request-Id} header when the
 * caller already supplied one (caps at 128 characters, ASCII-printable
 * only) — otherwise a fresh UUID is generated. It's published to:
 *   - the {@link MDC} under key {@code request_id} (so logback picks it up)
 *   - the request attribute {@link #ATTRIBUTE} (read by controllers)
 *   - the response as header {@link #HEADER}
 */
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = "request_id";
    public static final String ATTRIBUTE = "muxai.requestId";

    private static final int MAX_INBOUND_LENGTH = 128;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String requestId = sanitize(request.getHeader(HEADER));
        if (requestId == null) {
            requestId = UUID.randomUUID().toString();
        }
        request.setAttribute(ATTRIBUTE, requestId);
        response.setHeader(HEADER, requestId);
        MDC.put(MDC_KEY, requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    /**
     * Accept only short, printable-ASCII ids. An attacker-supplied value
     * flows straight into logs and the response header, so we refuse
     * control characters and anything over {@link #MAX_INBOUND_LENGTH}.
     */
    static String sanitize(String inbound) {
        if (inbound == null) return null;
        String trimmed = inbound.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_INBOUND_LENGTH) return null;
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c < 0x20 || c > 0x7E) return null;
        }
        return trimmed;
    }
}
