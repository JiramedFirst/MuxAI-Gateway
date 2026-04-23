package com.muxai.gateway.hotreload;

import com.muxai.gateway.config.GatewayProperties;
import com.muxai.gateway.observability.RequestMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigWatcherTest {

    private static final String VALID_YAML = """
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

    @Test
    void parsesProvidersAndApiKeysFromYaml() throws Exception {
        GatewayProperties props = ConfigWatcher.parseAndBind(VALID_YAML.getBytes(StandardCharsets.UTF_8));

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

    @Test
    void validReloadIncrementsSuccessCounter(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("providers.yml");
        Files.writeString(file, VALID_YAML);
        Fixture f = fixture(file);

        f.watcher.check();

        assertThat(f.registry.counter("muxai.config.reload.total", "outcome", "success")
                .count()).isEqualTo(1.0);
        assertThat(f.runtime.current().providersOrEmpty()).hasSize(1);
    }

    @Test
    void unchangedFileSkipsReloadAndEmitsNoMetric(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("providers.yml");
        Files.writeString(file, VALID_YAML);
        Fixture f = fixture(file);

        f.watcher.check();
        f.watcher.check(); // mtime unchanged -> no-op

        assertThat(f.registry.counter("muxai.config.reload.total", "outcome", "success")
                .count()).isEqualTo(1.0);
    }

    @Test
    void invalidConfigIncrementsInvalidCounterAndKeepsPreviousConfig(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("providers.yml");
        // Route references a provider that does not exist — passes YAML parse,
        // fails ConfigValidator.
        Files.writeString(file, """
                muxai:
                  providers:
                    - id: p1
                      type: openai
                      base-url: https://example.com/v1
                      api-key: sk-x
                      models: [gpt-4o]
                  routes:
                    - match: {model: gpt-4o}
                      primary: {provider: does-not-exist}
                """);
        Fixture f = fixture(file);
        GatewayProperties before = f.runtime.current();

        f.watcher.check();

        assertThat(f.registry.counter("muxai.config.reload.total", "outcome", "invalid")
                .count()).isEqualTo(1.0);
        assertThat(f.runtime.current()).isSameAs(before);
    }

    @Test
    void malformedYamlIncrementsParseFailedCounter(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("providers.yml");
        // Unterminated flow sequence — SnakeYAML rejects this.
        Files.writeString(file, "muxai:\n  providers: [ {id: p1, type: openai\n");
        Fixture f = fixture(file);
        GatewayProperties before = f.runtime.current();

        f.watcher.check();

        assertThat(f.registry.counter("muxai.config.reload.total", "outcome", "parse_failed")
                .count()).isEqualTo(1.0);
        assertThat(f.runtime.current()).isSameAs(before);
    }

    @Test
    void ioErrorIncrementsIoErrorCounter(@TempDir Path dir) {
        // A directory passes Files.exists and has a mtime, but readAllBytes
        // throws IOException — simulating a file that's present but unreadable.
        Fixture f = fixture(dir);

        f.watcher.check();

        assertThat(f.registry.counter("muxai.config.reload.total", "outcome", "io_error")
                .count()).isEqualTo(1.0);
    }

    @Test
    void missingFileIsSilent(@TempDir Path dir) {
        Path nonexistent = dir.resolve("nope.yml");
        Fixture f = fixture(nonexistent);

        f.watcher.check();

        assertThat(f.registry.find("muxai.config.reload.total").counters()).isEmpty();
    }

    private record Fixture(ConfigWatcher watcher, ConfigRuntime runtime, SimpleMeterRegistry registry) {}

    private Fixture fixture(Path configPath) {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RequestMetrics metrics = new RequestMetrics(registry);
        ConfigRuntime runtime = new ConfigRuntime(
                new GatewayProperties(java.util.List.of(), java.util.List.of(), java.util.List.of()),
                metrics);
        HotReloadProperties props = new HotReloadProperties(
                Boolean.TRUE, configPath.toString(), 5_000L);
        ConfigWatcher watcher = new ConfigWatcher(props, runtime, metrics);
        return new Fixture(watcher, runtime, registry);
    }
}
