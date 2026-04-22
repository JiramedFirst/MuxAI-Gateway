package com.muxai.gateway.provider;

public class ProviderException extends RuntimeException {
    public enum Code {
        INVALID_REQUEST(false, 400),
        AUTH_FAILED(false, 502),
        RATE_LIMITED(true, 429),
        PROVIDER_ERROR(true, 502),
        TIMEOUT(true, 504),
        NETWORK_ERROR(true, 502),
        UNSUPPORTED(false, 400);

        public final boolean retryable;
        public final int clientStatus;
        Code(boolean retryable, int clientStatus) {
            this.retryable = retryable;
            this.clientStatus = clientStatus;
        }
    }

    private final Code code;
    private final String providerId;

    public ProviderException(Code code, String providerId, String message) {
        super(message);
        this.code = code;
        this.providerId = providerId;
    }

    public ProviderException(Code code, String providerId, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.providerId = providerId;
    }

    public Code code() { return code; }
    public String providerId() { return providerId; }
}
