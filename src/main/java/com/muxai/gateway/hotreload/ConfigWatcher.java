package com.muxai.gateway.hotreload;

import com.muxai.gateway.config.ConfigValidator;
import com.muxai.gateway.config.GatewayProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private ScheduledExecutorService scheduler;
    private volatile long lastModifiedMillis;

    public ConfigWatcher(HotReloadProperties props, ConfigRuntime runtime) {
        this.props = props;
        this.runtime = runtime;
    }

    @PostConstruct
    public void start() {
        if (!props.enabledOrDefault()) return;
        Path path = Paths.get(props.pathOrDefault());
        if (!Files.exists(path)) {
            log.warn("hot-reload path does not exist, disabling: {}", path);
            return;
        }
        try {
            lastModifiedMillis = Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            log.warn("hot-reload initial mtime read failed: {}", e.getMessage());
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "muxai-config-watcher");
            t.setDaemon(true);
            return t;
        });
        long interval = props.intervalMsOrDefault();
        scheduler.scheduleAtFixedRate(this::check, interval, interval, TimeUnit.MILLISECONDS);
        log.info("config hot-reload enabled: path={} interval_ms={}", path, interval);
    }

    @PreDestroy
    public void stop() {
        if (scheduler != null) scheduler.shutdownNow();
    }

    void check() {
        try {
            Path path = Paths.get(props.pathOrDefault());
            if (!Files.exists(path)) return;
            long mt = Files.getLastModifiedTime(path).toMillis();
            if (mt == lastModifiedMillis) return;
            GatewayProperties parsed = parseAndBind(Files.readAllBytes(path));
            try {
                new ConfigValidator(parsed).validate();
            } catch (IllegalStateException bad) {
                log.warn("hot-reload rejected — config invalid, keeping previous: {}", bad.getMessage());
                lastModifiedMillis = mt;
                return;
            }
            runtime.replace(parsed);
            lastModifiedMillis = mt;
        } catch (Exception e) {
            log.warn("hot-reload tick failed: {}", e.getMessage());
        }
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
