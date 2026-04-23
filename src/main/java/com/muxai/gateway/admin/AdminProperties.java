package com.muxai.gateway.admin;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Admin-surface configuration. All fields optional with sensible defaults so
 * the gateway runs without an explicit admin block in application.yml.
 */
@ConfigurationProperties(prefix = "muxai.admin")
public record AdminProperties(
        Long rotationGraceSeconds,
        String runtimeKeysPath
) {
    public long rotationGraceSecondsOrDefault() {
        return rotationGraceSeconds != null ? rotationGraceSeconds : 600L;
    }

    public String runtimeKeysPathOrDefault() {
        return runtimeKeysPath != null && !runtimeKeysPath.isBlank()
                ? runtimeKeysPath
                : "./config/runtime-keys.yml";
    }
}
