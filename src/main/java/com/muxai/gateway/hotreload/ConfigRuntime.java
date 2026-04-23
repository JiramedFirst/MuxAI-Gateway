package com.muxai.gateway.hotreload;

import com.muxai.gateway.config.GatewayProperties;
import com.muxai.gateway.observability.RequestMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Mutable holder for the current {@link GatewayProperties}. Beans that need
 * to react to config changes (API-key filter, rate limiter, route matcher)
 * read from this object instead of injecting {@code GatewayProperties}
 * directly, and register a listener via {@link #addListener(Consumer)} to
 * rebuild their internal state when the file is hot-reloaded.
 *
 * <p>Only fields that can be safely swapped at request time — routes, API
 * keys, rate-limit entries — are surfaced here. Provider definitions are
 * read once at startup by {@link com.muxai.gateway.provider.ProviderRegistry}
 * because hot-swapping WebClient connection pools is out of scope.
 */
@Component
public class ConfigRuntime {

    private static final Logger log = LoggerFactory.getLogger(ConfigRuntime.class);

    private final AtomicReference<GatewayProperties> current;
    private final List<Consumer<GatewayProperties>> listeners = new CopyOnWriteArrayList<>();
    private final RequestMetrics metrics;

    public ConfigRuntime(GatewayProperties initial, RequestMetrics metrics) {
        this.current = new AtomicReference<>(initial);
        this.metrics = metrics;
    }

    public GatewayProperties current() { return current.get(); }

    public void replace(GatewayProperties next) {
        GatewayProperties prev = current.getAndSet(next);
        boolean listenerFailed = false;
        for (Consumer<GatewayProperties> l : listeners) {
            try {
                l.accept(next);
            } catch (Exception e) {
                listenerFailed = true;
                log.warn("config hot-reload outcome=listener_error reason=\"{}\"", e.getMessage(), e);
            }
        }
        if (listenerFailed) {
            metrics.recordConfigReload("listener_error");
        }
        log.info("config hot-reload applied routes={}->{} api_keys={}->{}",
                prev.routesOrEmpty().size(), next.routesOrEmpty().size(),
                prev.apiKeysOrEmpty().size(), next.apiKeysOrEmpty().size());
    }

    public void addListener(Consumer<GatewayProperties> listener) {
        listeners.add(listener);
    }
}
