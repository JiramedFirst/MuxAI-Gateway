package com.muxai.gateway.admin;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.muxai.gateway.config.GatewayProperties;
import com.muxai.gateway.config.GatewayProperties.ApiKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runtime overlay for api-keys. Loaded from {@code runtime-keys.yml} on every
 * providers.yml reload and merged into GatewayProperties before validation.
 *
 * Why an overlay file and not in-memory state: providers.yml is the declared
 * source of truth, polled for mtime changes. Rotation state written only to
 * memory would be silently clobbered on the next reload. An overlay keeps
 * rotation state durable without fighting providers.yml.
 *
 * Merge rule: keys are deduplicated by their Bearer-token string; overlay
 * entries override providers.yml entries with the same token. This is how
 * rotation "expires" an existing key — the overlay entry adds expires-at.
 */
@Component
public class ApiKeyOverlay {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyOverlay.class);

    private final Path path;
    private final YAMLMapper writer;

    public ApiKeyOverlay(AdminProperties adminProps) {
        this.path = Paths.get(adminProps.runtimeKeysPathOrDefault());
        // Kebab-case to match providers.yml style; Spring Boot binds both on
        // read but the file should look consistent for humans eyeballing it.
        this.writer = YAMLMapper.builder()
                .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
                .propertyNamingStrategy(PropertyNamingStrategies.KEBAB_CASE)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .findAndAddModules()
                .build();
    }

    public Path path() {
        return path;
    }

    /**
     * Read runtime-keys.yml and return its api-keys list. Empty list if the
     * file is missing (common case — no rotation in flight).
     */
    public List<ApiKey> load() {
        if (!Files.exists(path)) return List.of();
        try {
            byte[] bytes = Files.readAllBytes(path);
            if (bytes.length == 0) return List.of();
            YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
            var sources = loader.load("muxai-runtime-keys", new ByteArrayResource(bytes));
            if (sources.isEmpty()) return List.of();
            // Bind under "muxai" prefix even though the file contents start at
            // "api-keys" — this lets operators point the overlay at a snippet
            // shaped like `muxai.api-keys: [...]` OR just `api-keys: [...]`.
            Binder binder = new Binder(ConfigurationPropertySources.from(sources));
            GatewayProperties shell = binder.bindOrCreate("muxai", GatewayProperties.class);
            return shell.apiKeysOrEmpty();
        } catch (Exception e) {
            log.warn("runtime-keys overlay load failed path={} reason=\"{}\"", path, e.getMessage());
            return List.of();
        }
    }

    /**
     * Return a new GatewayProperties with the overlay's api-keys merged in.
     * Keys are deduplicated by token string; overlay entries win ties.
     */
    public GatewayProperties merge(GatewayProperties base) {
        List<ApiKey> overlay = load();
        if (overlay.isEmpty()) return base;

        Map<String, ApiKey> byToken = new LinkedHashMap<>();
        for (ApiKey k : base.apiKeysOrEmpty()) {
            if (k.key() != null) byToken.put(k.key(), k);
        }
        for (ApiKey k : overlay) {
            if (k.key() != null) byToken.put(k.key(), k);
        }
        return new GatewayProperties(base.providers(), base.routes(),
                new ArrayList<>(byToken.values()));
    }

    /**
     * Atomically replace the overlay file with the given keys. Writes to a
     * .tmp file then renames — avoids a half-written file being picked up by
     * a concurrent reload.
     */
    public synchronized void write(List<ApiKey> keys) throws IOException {
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null && !Files.exists(parent)) {
            Files.createDirectories(parent);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> muxai = new LinkedHashMap<>();
        muxai.put("api-keys", keys);
        root.put("muxai", muxai);

        Path tmp = path.resolveSibling(path.getFileName().toString() + ".tmp");
        writer.writeValue(tmp.toFile(), root);
        Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
}
