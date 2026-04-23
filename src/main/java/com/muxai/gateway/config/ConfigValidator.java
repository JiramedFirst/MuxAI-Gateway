package com.muxai.gateway.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Fails fast at startup if providers.yml is malformed. Prefer a loud crash
 * at boot over silently-degraded routing at request time — the first
 * symptom of a typo today is a 404 or 502 on a customer call.
 *
 * Checks: required fields on providers; duplicate provider/app-id/key ids;
 * routes that reference undefined providers; nonsensical rate limits.
 */
@Component
public class ConfigValidator {

    private static final Logger log = LoggerFactory.getLogger(ConfigValidator.class);

    private static final Set<String> SUPPORTED_TYPES = Set.of("openai", "anthropic");
    private static final Set<String> SUPPORTED_ROLES = Set.of(
            GatewayProperties.ROLE_ADMIN, GatewayProperties.ROLE_APP);

    private final GatewayProperties props;

    public ConfigValidator(GatewayProperties props) {
        this.props = props;
    }

    @PostConstruct
    public void validate() {
        List<String> errors = new ArrayList<>();
        Set<String> providerIds = validateProviders(errors);
        validateRoutes(providerIds, errors);
        validateApiKeys(errors);

        if (!errors.isEmpty()) {
            String joined = String.join("\n  - ", errors);
            throw new IllegalStateException(
                    "Invalid MuxAI configuration — fix providers.yml before retrying:\n  - " + joined);
        }
        log.info("Configuration validated: providers={} routes={} api-keys={}",
                props.providersOrEmpty().size(),
                props.routesOrEmpty().size(),
                props.apiKeysOrEmpty().size());
    }

    private Set<String> validateProviders(List<String> errors) {
        Set<String> ids = new HashSet<>();
        List<ProviderProperties> providers = props.providersOrEmpty();
        if (providers.isEmpty()) {
            errors.add("no providers configured — gateway cannot route any request");
            return ids;
        }
        for (int i = 0; i < providers.size(); i++) {
            ProviderProperties p = providers.get(i);
            String prefix = "providers[" + i + "]";
            if (isBlank(p.id())) {
                errors.add(prefix + ": id is required");
                continue;
            }
            if (!ids.add(p.id())) {
                errors.add(prefix + " (id=" + p.id() + "): duplicate provider id");
            }
            if (isBlank(p.type())) {
                errors.add(prefix + " (id=" + p.id() + "): type is required");
            } else if (!SUPPORTED_TYPES.contains(p.type().toLowerCase(Locale.ROOT))) {
                errors.add(prefix + " (id=" + p.id() + "): unknown type '" + p.type()
                        + "' (supported: " + SUPPORTED_TYPES + ")");
            }
            if (isBlank(p.baseUrl())) {
                errors.add(prefix + " (id=" + p.id() + "): base-url is required");
            } else {
                try {
                    URI uri = new URI(p.baseUrl());
                    if (uri.getScheme() == null || uri.getHost() == null) {
                        errors.add(prefix + " (id=" + p.id() + "): base-url '" + p.baseUrl()
                                + "' is missing a scheme or host");
                    }
                } catch (URISyntaxException e) {
                    errors.add(prefix + " (id=" + p.id() + "): base-url '" + p.baseUrl()
                            + "' is not a valid URI: " + e.getMessage());
                }
            }
            if (p.timeoutMs() != null && p.timeoutMs() <= 0) {
                errors.add(prefix + " (id=" + p.id() + "): timeout-ms must be positive");
            }
            if (isBlank(p.apiKey())) {
                // Not fatal — local Ollama / self-hosted backends don't need keys —
                // but warn so missing ${OPENAI_API_KEY:} env vars don't silently produce 401s.
                log.warn("Provider '{}' has no api-key — upstream auth will likely fail if this isn't a local endpoint",
                        p.id());
            }
            validatePricing(prefix + " (id=" + p.id() + ")", p.pricingOrEmpty(), errors);
        }
        return ids;
    }

    private void validatePricing(String prefix,
                                 java.util.Map<String, ProviderProperties.ModelPricing> pricing,
                                 List<String> errors) {
        for (var entry : pricing.entrySet()) {
            String model = entry.getKey();
            ProviderProperties.ModelPricing mp = entry.getValue();
            if (mp == null) {
                errors.add(prefix + ".pricing[" + model + "]: value is null");
                continue;
            }
            if (mp.inputPer1MUsd() < 0 || mp.outputPer1MUsd() < 0) {
                errors.add(prefix + ".pricing[" + model + "]: "
                        + "input-per-1m-usd and output-per-1m-usd must be >= 0");
            }
        }
    }

    private void validateRoutes(Set<String> providerIds, List<String> errors) {
        List<RouteProperties> routes = props.routesOrEmpty();
        if (routes.isEmpty()) {
            errors.add("no routes configured — every authenticated request would return 'no route matches'");
            return;
        }
        boolean sawCatchAll = false;
        for (int i = 0; i < routes.size(); i++) {
            RouteProperties r = routes.get(i);
            String prefix = "routes[" + i + "]";
            if (r.primary() == null) {
                errors.add(prefix + ": primary is required");
            } else {
                validateStep(prefix + ".primary", r.primary(), providerIds, errors);
            }
            for (int j = 0; j < r.fallbackOrEmpty().size(); j++) {
                validateStep(prefix + ".fallback[" + j + "]", r.fallbackOrEmpty().get(j),
                        providerIds, errors);
            }
            RouteProperties.Match m = r.match();
            if (m == null || (m.appId() == null && m.model() == null)) {
                sawCatchAll = true;
            } else if (sawCatchAll) {
                // Routes are matched top-down. A specific rule after a catch-all is dead code —
                // reject it so the author notices before production traffic reveals it.
                errors.add(prefix + ": unreachable — a catch-all route appears before this one");
            }
        }
    }

    private void validateStep(String path, RouteProperties.Step step,
                              Set<String> providerIds, List<String> errors) {
        if (step == null) {
            errors.add(path + ": step is null");
            return;
        }
        if (isBlank(step.provider())) {
            errors.add(path + ": provider is required");
        } else if (!providerIds.contains(step.provider())) {
            errors.add(path + ": references undefined provider '" + step.provider() + "'");
        }
    }

    private void validateApiKeys(List<String> errors) {
        List<GatewayProperties.ApiKey> keys = props.apiKeysOrEmpty();
        if (keys.isEmpty()) {
            errors.add("no api-keys configured — every request would be rejected as unauthenticated");
            return;
        }
        Set<String> seenKeys = new HashSet<>();
        for (int i = 0; i < keys.size(); i++) {
            GatewayProperties.ApiKey k = keys.get(i);
            String prefix = "api-keys[" + i + "]";
            if (isBlank(k.key())) {
                errors.add(prefix + ": key is required");
            } else if (!seenKeys.add(k.key())) {
                errors.add(prefix + ": duplicate key value (each Bearer token must be unique)");
            }
            if (isBlank(k.appId())) {
                errors.add(prefix + ": app-id is required (used for rate-limit bucketing and logging)");
            }
            if (k.rateLimitPerMin() != null && k.rateLimitPerMin() < 0) {
                errors.add(prefix + ": rate-limit-per-min must be >= 0 (0 or absent disables limiting)");
            }
            if (k.role() != null
                    && !SUPPORTED_ROLES.contains(k.role().toLowerCase(Locale.ROOT))) {
                errors.add(prefix + ": unknown role '" + k.role()
                        + "' (supported: " + SUPPORTED_ROLES + ")");
            }
            if (k.dailyBudgetUsd() != null && k.dailyBudgetUsd() < 0) {
                errors.add(prefix + ": daily-budget-usd must be >= 0");
            }
            // expiresAt in the past is not an error — operators legitimately leave
            // expired keys in the file until cleanup. The auth filter rejects them
            // at request time; the config just ignores them.
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
