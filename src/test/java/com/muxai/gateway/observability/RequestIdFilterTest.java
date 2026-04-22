package com.muxai.gateway.observability;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();

    @Test
    void generatesUuidWhenHeaderMissing() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        MockHttpServletResponse resp = new MockHttpServletResponse();
        FilterChain chain = (rq, rs) -> {
            // While the chain runs, MDC must carry the id.
            String inChain = MDC.get(RequestIdFilter.MDC_KEY);
            assertThat(inChain).isNotBlank();
            assertThat(rq.getAttribute(RequestIdFilter.ATTRIBUTE)).isEqualTo(inChain);
        };

        filter.doFilter(req, resp, chain);

        assertThat(resp.getHeader(RequestIdFilter.HEADER)).isNotBlank();
        // MDC must be cleaned up so thread reuse doesn't leak the value.
        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void propagatesInboundHeaderUnchanged() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader(RequestIdFilter.HEADER, "caller-supplied-abc-123");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, (rq, rs) -> {});

        assertThat(resp.getHeader(RequestIdFilter.HEADER)).isEqualTo("caller-supplied-abc-123");
    }

    @Test
    void sanitizeRejectsControlChars() {
        assertThat(RequestIdFilter.sanitize("ok-123")).isEqualTo("ok-123");
        assertThat(RequestIdFilter.sanitize("line\nbreak")).isNull();
        assertThat(RequestIdFilter.sanitize("tab\there")).isNull();
        assertThat(RequestIdFilter.sanitize("")).isNull();
        assertThat(RequestIdFilter.sanitize(null)).isNull();
    }

    @Test
    void sanitizeRejectsOverlongInput() {
        String tooLong = "a".repeat(200);
        assertThat(RequestIdFilter.sanitize(tooLong)).isNull();
    }

    @Test
    void mdcIsClearedEvenWhenChainThrows() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        MockHttpServletResponse resp = new MockHttpServletResponse();
        FilterChain blowingChain = (rq, rs) -> { throw new RuntimeException("boom"); };

        try {
            filter.doFilter(req, resp, blowingChain);
        } catch (Exception ignored) {}

        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
    }
}
