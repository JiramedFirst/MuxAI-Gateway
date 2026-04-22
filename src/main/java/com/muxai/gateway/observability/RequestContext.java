package com.muxai.gateway.observability;

import jakarta.servlet.http.HttpServletRequest;

import java.util.UUID;

/**
 * Controllers call {@link #requestId(HttpServletRequest)} to get the
 * correlation id established by {@link RequestIdFilter}. The fallback
 * UUID only matters if the filter was disabled in a test — in
 * production the attribute is always set.
 */
public final class RequestContext {

    private RequestContext() {}

    public static String requestId(HttpServletRequest request) {
        if (request != null) {
            Object attr = request.getAttribute(RequestIdFilter.ATTRIBUTE);
            if (attr instanceof String s && !s.isEmpty()) return s;
        }
        return UUID.randomUUID().toString();
    }
}
