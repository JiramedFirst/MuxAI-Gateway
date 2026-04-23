package com.muxai.gateway.observability;

import com.muxai.gateway.cost.BudgetGuard;
import com.muxai.gateway.cost.PricingTable;
import com.muxai.gateway.provider.ProviderException;
import com.muxai.gateway.provider.model.Usage;
import com.muxai.gateway.router.Router;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
public class RequestMetrics {

    private static final Logger log = LoggerFactory.getLogger(RequestMetrics.class);

    private final MeterRegistry registry;
    // ObjectProvider (lazy) resolution for PricingTable + BudgetGuard breaks the
    // otherwise-circular dependency ConfigRuntime -> RequestMetrics -> PricingTable
    // -> ConfigRuntime. Both are resolved on every cost-recording call; null-safe
    // when absent (tests or bootstrap ordering).
    private final ObjectProvider<PricingTable> pricingTableProvider;
    private final ObjectProvider<BudgetGuard> budgetGuardProvider;

    public RequestMetrics(MeterRegistry registry) {
        this(registry, emptyProvider(), emptyProvider());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public RequestMetrics(MeterRegistry registry,
                          ObjectProvider<PricingTable> pricingTableProvider,
                          ObjectProvider<BudgetGuard> budgetGuardProvider) {
        this.registry = registry;
        this.pricingTableProvider = pricingTableProvider;
        this.budgetGuardProvider = budgetGuardProvider;
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> emptyProvider() {
        return (ObjectProvider<T>) EMPTY_PROVIDER;
    }

    private static final ObjectProvider<Object> EMPTY_PROVIDER = new ObjectProvider<>() {
        @Override public Object getObject(Object... args) { return null; }
        @Override public Object getObject() { return null; }
        @Override public Object getIfAvailable() { return null; }
        @Override public Object getIfUnique() { return null; }
    };

    public void recordRequest(String appId, String route, String outcome) {
        Counter.builder("muxai.request.total")
                .description("Count of chat requests processed by the gateway")
                .tags(Tags.of(
                        Tag.of("app_id", safe(appId)),
                        Tag.of("route", safe(route)),
                        Tag.of("outcome", safe(outcome))))
                .register(registry)
                .increment();
    }

    public void recordProviderCall(String providerId, String outcome, long nanos) {
        Timer.builder("muxai.provider.call")
                .description("Latency of provider calls")
                .tags(Tags.of(
                        Tag.of("provider_id", safe(providerId)),
                        Tag.of("outcome", safe(outcome))))
                .register(registry)
                .record(nanos, TimeUnit.NANOSECONDS);
    }

    public void recordTokens(String providerId, String model, String direction, long count) {
        if (count <= 0) return;
        Counter.builder("muxai.tokens.total")
                .description("Tokens consumed/produced by providers")
                .tags(Tags.of(
                        Tag.of("provider_id", safe(providerId)),
                        Tag.of("model", safe(model)),
                        Tag.of("direction", safe(direction))))
                .register(registry)
                .increment(count);
    }

    /**
     * Record per-request USD cost computed from the provider's declared per-million
     * pricing in providers.yml. No-op when no pricing is wired (test contexts) or
     * when the (provider, model) pair has no declared rate.
     */
    public void recordCost(String appId, String providerId, String model,
                           long promptTokens, long completionTokens) {
        PricingTable pricing = pricingTableProvider.getIfAvailable();
        if (pricing == null) return;
        double usd = pricing.usdFor(providerId, model, promptTokens, completionTokens);
        if (usd <= 0.0) return;
        Counter.builder("muxai.cost.usd.total")
                .description("Cumulative provider spend in USD, attributed by app/provider/model")
                .baseUnit("USD")
                .tags(Tags.of(
                        Tag.of("app_id", safe(appId)),
                        Tag.of("provider_id", safe(providerId)),
                        Tag.of("model", safe(model))))
                .register(registry)
                .increment(usd);
        // Always update the budget tally — even when budget enforcement is off,
        // operators can dry-run caps before flipping the switch.
        BudgetGuard budget = budgetGuardProvider.getIfAvailable();
        if (budget != null) {
            budget.record(appId, usd);
        }
    }

    /**
     * Record a request rejected by the rate-limit filter. Dedicated counter so
     * 429s don't pollute {@code muxai.request.total} with a synthetic "rate_limited"
     * value in the {@code route} label (which is reserved for real route descriptions).
     */
    public void recordCacheHit(String appId, String endpoint) {
        Counter.builder("muxai.cache.hit.total")
                .description("Count of requests served from the semantic cache")
                .tags(Tags.of(
                        Tag.of("app_id", safe(appId)),
                        Tag.of("endpoint", safe(endpoint))))
                .register(registry)
                .increment();
    }

    public void recordCacheMiss(String appId, String endpoint) {
        Counter.builder("muxai.cache.miss.total")
                .description("Count of requests that missed the semantic cache")
                .tags(Tags.of(
                        Tag.of("app_id", safe(appId)),
                        Tag.of("endpoint", safe(endpoint))))
                .register(registry)
                .increment();
    }

    public void recordPiiRedaction(String appId, String kind) {
        Counter.builder("muxai.pii.redacted.total")
                .description("Count of PII tokens redacted by the gateway before forwarding to providers")
                .tags(Tags.of(
                        Tag.of("app_id", safe(appId)),
                        Tag.of("kind", safe(kind))))
                .register(registry)
                .increment();
    }

    public void recordStreamSuccess(String requestId, String appId, String endpoint,
                                    String modelRequested, long latencyMs) {
        recordRequest(appId, "stream", "success");
        log.info("request_id={} app_id={} endpoint={} model_requested={} route_matched=stream " +
                        "provider_attempted= provider_succeeded=stream model_actual={} " +
                        "latency_ms={} prompt_tokens=0 completion_tokens=0 outcome=success error_code=null",
                requestId, appId, endpoint, modelRequested, modelRequested, latencyMs);
    }

    public void recordRateLimited(String appId) {
        Counter.builder("muxai.request.rate_limited.total")
                .description("Count of requests rejected by the per-app rate limiter")
                .tags(Tags.of(Tag.of("app_id", safe(appId))))
                .register(registry)
                .increment();
    }

    /**
     * Record a config hot-reload tick outcome. Tag values are stable strings
     * ({@code success}, {@code invalid}, {@code parse_failed}, {@code io_error},
     * {@code listener_error}) so downstream alerts can filter on them.
     */
    public void recordConfigReload(String outcome) {
        Counter.builder("muxai.config.reload.total")
                .description("Count of config hot-reload ticks by outcome")
                .tags(Tags.of(Tag.of("outcome", safe(outcome))))
                .register(registry)
                .increment();
    }

    /**
     * Record a successful request: increments the request counter, emits token counters,
     * and writes the structured success log line. Token extraction handles null usage.
     */
    public <T> void recordSuccess(String requestId, String appId, String endpoint,
                                  String modelRequested, Router.RoutedResult<T> result,
                                  long latencyMs, Usage usage) {
        int promptTokens = usage != null && usage.promptTokens() != null ? usage.promptTokens() : 0;
        int completionTokens = usage != null && usage.completionTokens() != null ? usage.completionTokens() : 0;

        recordRequest(appId, result.decision().description(), "success");
        if (result.providerSucceeded() != null) {
            recordTokens(result.providerSucceeded(), result.modelActual(), "prompt", promptTokens);
            recordTokens(result.providerSucceeded(), result.modelActual(), "completion", completionTokens);
            recordCost(appId, result.providerSucceeded(), result.modelActual(),
                    promptTokens, completionTokens);
        }

        log.info("request_id={} app_id={} endpoint={} model_requested={} route_matched={} " +
                        "provider_attempted={} provider_succeeded={} model_actual={} " +
                        "latency_ms={} prompt_tokens={} completion_tokens={} outcome=success error_code=null",
                requestId, appId, endpoint, modelRequested, result.decision().description(),
                String.join(",", result.providersAttempted()),
                result.providerSucceeded(), result.modelActual(),
                latencyMs, promptTokens, completionTokens);
    }

    /**
     * Record a failed request: increments the request counter with outcome=error and
     * writes the structured error log line.
     */
    public void recordFailure(String requestId, String appId, String endpoint,
                              String modelRequested, long latencyMs, ProviderException pe) {
        recordRequest(appId, "unknown", "error");
        log.info("request_id={} app_id={} endpoint={} model_requested={} route_matched=unknown " +
                        "provider_attempted= provider_succeeded=null model_actual=null " +
                        "latency_ms={} prompt_tokens=0 completion_tokens=0 outcome=error error_code={}",
                requestId, appId, endpoint, modelRequested, latencyMs, pe.code());
    }

    private static String safe(String s) {
        return s == null ? "unknown" : s;
    }
}
