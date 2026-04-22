package com.muxai.gateway.provider;

import com.muxai.gateway.config.GatewayProperties;
import com.muxai.gateway.config.ProviderProperties;
import com.muxai.gateway.provider.anthropic.AnthropicProviderFactory;
import com.muxai.gateway.provider.openai.OpenAiProviderFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@Configuration
public class ProviderRegistry {

    private static final Logger log = LoggerFactory.getLogger(ProviderRegistry.class);

    @Bean
    public Map<String, LlmProvider> providers(
            GatewayProperties props,
            OpenAiProviderFactory openAiFactory,
            AnthropicProviderFactory anthropicFactory) {

        Map<String, LlmProvider> map = new LinkedHashMap<>();
        for (ProviderProperties pp : props.providersOrEmpty()) {
            LlmProvider provider = switch (pp.type()) {
                case "openai" -> openAiFactory.create(pp);
                case "anthropic" -> anthropicFactory.create(pp);
                default -> throw new IllegalArgumentException(
                        "Unknown provider type: " + pp.type() + " (id=" + pp.id() + ")");
            };
            if (map.containsKey(pp.id())) {
                throw new IllegalArgumentException("Duplicate provider id: " + pp.id());
            }
            map.put(pp.id(), provider);
            log.info("Registered provider id={} type={} baseUrl={}",
                    pp.id(), pp.type(), pp.baseUrl());
        }
        return Map.copyOf(map);
    }

    @Bean
    public Lookup providerLookup(Map<String, LlmProvider> providers) {
        return new Lookup(providers);
    }

    public static final class Lookup {
        private final Map<String, LlmProvider> providers;

        public Lookup(Map<String, LlmProvider> providers) {
            this.providers = new HashMap<>(providers);
        }

        public LlmProvider require(String id) {
            LlmProvider p = providers.get(id);
            if (p == null) {
                throw new ProviderException(
                        ProviderException.Code.INVALID_REQUEST,
                        "registry",
                        "Unknown provider id: " + id);
            }
            return p;
        }

        public Optional<LlmProvider> find(String id) {
            return Optional.ofNullable(providers.get(id));
        }

        public Collection<LlmProvider> all() {
            return providers.values();
        }
    }
}
