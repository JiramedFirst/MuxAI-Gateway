package com.muxai.gateway.admin;

import com.muxai.gateway.config.GatewayProperties;
import com.muxai.gateway.config.GatewayProperties.ApiKey;
import com.muxai.gateway.hotreload.ConfigRuntime;
import com.muxai.gateway.hotreload.ConfigWatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Key rotation: issue a new Bearer token for an existing app without
 * interrupting in-flight callers. The old key keeps working until
 * {@code rotation-grace-seconds} elapses, giving operators time to roll
 * the new key out to clients.
 *
 * Rotation state is persisted to runtime-keys.yml (the overlay file) so it
 * survives restarts and providers.yml edits. See {@link ApiKeyOverlay}.
 */
@Service
public class KeyRotationService {

    private static final Logger log = LoggerFactory.getLogger(KeyRotationService.class);

    private static final SecureRandom RANDOM = new SecureRandom();

    private final ConfigRuntime runtime;
    private final ApiKeyOverlay overlay;
    private final ConfigWatcher watcher;
    private final AdminProperties adminProps;

    public KeyRotationService(ConfigRuntime runtime,
                              ApiKeyOverlay overlay,
                              ConfigWatcher watcher,
                              AdminProperties adminProps) {
        this.runtime = runtime;
        this.overlay = overlay;
        this.watcher = watcher;
        this.adminProps = adminProps;
    }

    public RotationResult rotate(String oldKeyToken) throws IOException {
        if (oldKeyToken == null || oldKeyToken.isBlank()) {
            throw new IllegalArgumentException("'key' field is required");
        }

        GatewayProperties current = runtime.current();
        ApiKey oldKey = current.apiKeysOrEmpty().stream()
                .filter(k -> oldKeyToken.equals(k.key()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "No active key matches the supplied token"));

        Instant now = Instant.now();
        if (oldKey.expiresAt() != null && oldKey.expiresAt().isAfter(now)) {
            throw new IllegalArgumentException(
                    "Key is already in rotation (expires at " + oldKey.expiresAt() + ")");
        }

        long graceSeconds = adminProps.rotationGraceSecondsOrDefault();
        Instant oldExpiresAt = now.plusSeconds(graceSeconds);
        String newToken = generateToken();

        ApiKey oldRotating = new ApiKey(
                oldKey.key(), oldKey.appId(), oldKey.rateLimitPerMin(),
                oldKey.allowedModels(), oldKey.allowedEndpoints(), oldKey.role(),
                oldExpiresAt, oldKey.dailyBudgetUsd());
        ApiKey fresh = new ApiKey(
                newToken, oldKey.appId(), oldKey.rateLimitPerMin(),
                oldKey.allowedModels(), oldKey.allowedEndpoints(), oldKey.role(),
                null, oldKey.dailyBudgetUsd());

        // Merge into existing overlay by token so we don't lose other
        // in-flight rotations for other apps.
        List<ApiKey> existing = overlay.load();
        Map<String, ApiKey> byToken = new LinkedHashMap<>();
        for (ApiKey k : existing) {
            if (k.key() != null) byToken.put(k.key(), k);
        }
        byToken.put(oldRotating.key(), oldRotating);
        byToken.put(fresh.key(), fresh);
        overlay.write(new ArrayList<>(byToken.values()));
        watcher.reloadOverlay();

        log.info("key rotated app_id={} old_expires_at={} grace_seconds={}",
                oldKey.appId(), oldExpiresAt, graceSeconds);
        return new RotationResult(oldKey.appId(), newToken,
                mask(oldKey.key()), oldExpiresAt, graceSeconds);
    }

    private static String generateToken() {
        byte[] buf = new byte[16];
        RANDOM.nextBytes(buf);
        return "mgw_" + HexFormat.of().formatHex(buf);
    }

    private static String mask(String token) {
        if (token == null || token.length() <= 8) return "***";
        return token.substring(0, 4) + "…" + token.substring(token.length() - 4);
    }

    public record RotationResult(
            String appId,
            String newKey,
            String oldKeyMasked,
            Instant oldKeyExpiresAt,
            long graceSeconds) {}
}
