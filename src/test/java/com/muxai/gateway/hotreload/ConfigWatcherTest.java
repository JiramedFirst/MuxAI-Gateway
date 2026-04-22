package com.muxai.gateway.hotreload;

import com.muxai.gateway.config.GatewayProperties;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigWatcherTest {

    @Test
    void parsesProvidersAndApiKeysFromYaml() throws Exception {
        String yaml = """
                muxai:
                  providers:
                    - id: p1
                      type: openai
                      base-url: https://example.com/v1
                      api-key: sk-x
                      models: [gpt-4o]
                  routes:
                    - match: {model: gpt-4o}
                      primary: {provider: p1}
                  api-keys:
                    - key: mgw_abc
                      app-id: dev
                      rate-limit-per-min: 100
                """;

        GatewayProperties props = ConfigWatcher.parseAndBind(yaml.getBytes(StandardCharsets.UTF_8));

        assertThat(props.providersOrEmpty()).hasSize(1);
        assertThat(props.providersOrEmpty().get(0).id()).isEqualTo("p1");
        assertThat(props.providersOrEmpty().get(0).models()).containsExactly("gpt-4o");
        assertThat(props.routesOrEmpty()).hasSize(1);
        assertThat(props.routesOrEmpty().get(0).match().model()).isEqualTo("gpt-4o");
        assertThat(props.routesOrEmpty().get(0).primary().provider()).isEqualTo("p1");
        assertThat(props.apiKeysOrEmpty()).hasSize(1);
        assertThat(props.apiKeysOrEmpty().get(0).appId()).isEqualTo("dev");
        assertThat(props.apiKeysOrEmpty().get(0).rateLimitPerMin()).isEqualTo(100);
    }

    @Test
    void emptyYamlYieldsEmptyRecord() throws Exception {
        GatewayProperties props = ConfigWatcher.parseAndBind(new byte[0]);
        assertThat(props.providersOrEmpty()).isEmpty();
        assertThat(props.routesOrEmpty()).isEmpty();
        assertThat(props.apiKeysOrEmpty()).isEmpty();
    }
}
