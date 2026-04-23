package com.muxai.gateway.hotreload;

import com.muxai.gateway.admin.ApiKeyOverlay;
import com.muxai.gateway.config.ConfigValidator;
import com.muxai.gateway.config.GatewayProperties;
import com.muxai.gateway.observability.RequestMetrics;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Polls {@link HotReloadProperties#path()} and, on modification, re-parses
 * the YAML and swaps the runtime via {@link ConfigRuntime#replace}.
 *
 * <p>Polling (rather than {@code WatchService}) keeps the implementation
 * portable — file-system events are famously inconsistent across platforms
 * and container filesystems — and the default 5 s cadence is cheap: one
 * {@code stat} call and a short-circuit mtime comparison.
 *
 * <p>Reload rejects any config that fails {@link ConfigValidator}: a broken
 * YAML edit leaves the previous config live instead of crashing the process.
 */
@Component
public class ConfigWatcher {

    private static final Logger log = LoggerFactory.getLogger(ConfigWatcher.class);

    private final HotReloadProperties props;
    private final ConfigRuntime runtime;
    private final RequestMetrics metrics;
    // ObjectProvider to keep test constructors (which build ConfigWatcher
    // directly) from needing an overlay argument. getIfAvailable() returns
    // null in tests that don't wire the overlay; merge then becomes a no-op.
    private final ObjectProvider<ApiKeyOverlay> overlayProvider;

    private ScheduledExecutorService scheduler;
    private volatile long lastModifiedMillis;

    @Autowired
    public ConfigWatcher(HotReloadProperties props,
                         ConfigRuntime runtime,
                         RequestMetrics metrics,
                         ObjectProvider<ApiKeyOverlay> overlayProvider) {
        this.props = props;
        this.runtime = runtime;
        this.metrics = metrics;
        this.overlayProvider = overlayProvider;
    }

    // Backwards-compat ctor for tests that construct ConfigWatcher directly
    // without an overlay bean. Keeps RateLimit / ConfigWatcher tests simple.
    public ConfigWatcher(HotReloadProperties props, ConfigRuntime runtime, RequestMetrics metrics) {
        this(props, runtime, metrics, new ObjectProvider<>() {
            @Override public ApiKeyOverlay getObject(Object... args) { return null; }
            @Override public ApiKeyOverlay getObject() { return null; }
            @Override public ApiKeyOverlay getIfAvailable() { return null; }
            @Override public ApiKeyOverlay getIfUnique() { return null; }
        });
    }

    @PostConstruct
    public void start() {
        // Apply the runtime-keys overlay once at boot, regardless of whether
        // hot-reload polling is enabled. Rotation state must survive restart.
        applyOverlayAtBoot();

        if (!props.enabledOrDefault()) return;
        Path path = Paths.get(props.pathOrDefault());
        if (!Files.exists(path)) {
            log.warn("config hot-reload disabled outcome=missing_path path={}", path);
            return;
        }
        try {
            lastModifiedMillis = Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            log.warn("config hot-reload disabled outcome=io_error reason=\"{}\"", e.getMessage());
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "muxai-config-watcher");
            t.setDaemon(true);
            return t;
        });
        long interval = props.intervalMsOrDefault();
        scheduler.scheduleAtFixedRate(this::check, interval, interval, TimeUnit.MILLISECONDS);
        log.info("config hot-reload enabled path={} interval_ms={}", path, interval);
    }

    private void applyOverlayAtBoot() {
        ApiKeyOverlay overlay = overlayProvider.getIfAvailable();
        if (overlay == null) return;
        GatewayProperties current = runtime.current();
        GatewayProperties merged = overlay.merge(current);
        if (merged != current) {
            runtime.replace(merged);
            log.info("runtime-keys overlay applied at boot extra_keys={}",
                    merged.apiKeysOrEmpty().size() - current.apiKeysOrEmpty().size());
        }
    }

    /**
     * Re-read the overlay and merge it into the current runtime. Invoked by
     * AdminController after writing a rotation to runtime-keys.yml so the
     * change takes effect without waiting for the next mtime tick.
     */
    public void reloadOverlay() {
        ApiKeyOverlay overlay = overlayProvider.getIfAvailable();
        if (overlay == null) return;
        GatewayProperties merged = overlay.merge(runtime.current());
        runtime.replace(merged);
    }

    @PreDestroy
    public void stop() {
        if (scheduler != null) scheduler.shutdownNow();
    }

    void check() {
        Path path = Paths.get(props.pathOrDefault());
        if (!Files.exists(path)) return;

        long mt;
        byte[] yaml;
        try {
            mt = Files.getLastModifiedTime(path).toMillis();
            if (mt == lastModifiedMillis) return;
            yaml = Files.readAllBytes(path);
        } catch (IOException e) {
            log.warn("config hot-reload outcome=io_error reason=\"{}\"", e.getMessage());
            metrics.recordConfigReload("io_error");
            return;
        }

        GatewayProperties parsed;
        try {
            parsed = parseAndBind(yaml);
        } catch (Exception e) {
            // Advance mtime so a single corrupt save doesn't spam the log every tick;
            // the next distinct edit will retry.
            lastModifiedMillis = mt;
            log.warn("config hot-reload outcome=parse_failed reason=\"{}\"", e.getMessage());
            metrics.recordConfigReload("parse_failed");
            return;
        }

        try {
            new ConfigValidator(parsed).validate();
        } catch (IllegalStateException bad) {
            // Same rationale as parse_failed: advance mtime to avoid per-tick log spam.
            lastModifiedMillis = mt;
            log.warn("config hot-reload outcome=invalid reason=\"{}\"", bad.getMessage());
            metrics.recordConfigReload("invalid");
            return;
        }

        // Merge the runtime-keys overlay on every providers.yml reload so a
        // pending rotation survives edits elsewhere in the config file.
        ApiKeyOverlay overlay = overlayProvider.getIfAvailable();
        GatewayProperties merged = overlay != null ? overlay.merge(parsed) : parsed;

        runtime.replace(merged);
        lastModifiedMillis = mt;
        metrics.recordConfigReload("success");
    }

    /**
     * Parse {@code providers.yml} and bind the {@code muxai.*} slice to a
     * {@link GatewayProperties} record via Spring's {@link Binder} so the
     * kebab-case / env-var substitution rules match startup behaviour.
     */
    static GatewayProperties parseAndBind(byte[] yaml) throws IOException {
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        List<PropertySource<?>> sources = loader.load("muxai-hotreload",
                new ByteArrayResource(yaml));
        if (sources.isEmpty()) {
            return new GatewayProperties(List.of(), List.of(), List.of());
        }
        Binder binder = new Binder(ConfigurationPropertySources.from(sources));
        return binder.bindOrCreate("muxai", GatewayProperties.class);
    }
}
