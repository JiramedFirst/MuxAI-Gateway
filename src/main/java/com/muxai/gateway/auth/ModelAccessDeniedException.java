package com.muxai.gateway.auth;

import java.util.List;

/**
 * Thrown when an authenticated request targets a model the caller's API key
 * is not scoped to. Mapped to HTTP 403 by GlobalExceptionHandler.
 */
public class ModelAccessDeniedException extends RuntimeException {

    private final String appId;
    private final String requestedModel;

    public ModelAccessDeniedException(String appId, String requestedModel, List<String> allowed) {
        super("API key for app '" + appId + "' is not allowed to use model '"
                + requestedModel + "' (allowed: " + allowed + ")");
        this.appId = appId;
        this.requestedModel = requestedModel;
    }

    public String appId() {
        return appId;
    }

    public String requestedModel() {
        return requestedModel;
    }
}
