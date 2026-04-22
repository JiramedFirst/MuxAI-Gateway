# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Java 21 / Spring Boot 3.3 HTTP gateway that speaks the OpenAI Chat Completions
wire format on its inbound side and routes each request to a configured backend
(OpenAI, Anthropic, Ollama, vLLM, OpenTyphoon — anything OpenAI-compatible).
Routing lives entirely in `config/providers.yml`; client apps never change
when you swap a provider.

## Commands

```bash
# Full verify (unit + integration tests)
mvn clean verify

# Fast compile + unit tests only
mvn test

# Run a single test class or method
mvn -Dtest=RouterTest test
mvn -Dtest=RouterTest#routesToPrimary test

# Integration tests only (Failsafe plugin picks up *IT.java)
mvn verify -DskipUnitTests

# Run the gateway locally (reads ./config/providers.yml)
export OPENAI_API_KEY=sk-...
export ANTHROPIC_API_KEY=sk-ant-...
mvn spring-boot:run

# Package + Docker
mvn -DskipTests package
docker build -t muxai-gateway .
```

Integration tests (`*IT.java`) are wired through `maven-failsafe-plugin` and
run during `verify`, not `test`. They spin up WireMock against real WebClients
so they exercise the full filter chain + router; unit tests (`*Test.java`) run
in isolation under Surefire.

## Architecture

### Request lifecycle (chat completions)

```
HTTP → RequestIdFilter → ApiKeyAuthFilter → RateLimitFilter
     → ChatController → PiiRedactor → SemanticCache.lookup
     → Router.routeChat → RouteMatcher → LlmProvider (OpenAI|Anthropic)
     → SemanticCache.store → response
```

1. `RequestIdFilter` (order `Integer.MIN_VALUE`) stamps every request with
   `X-Request-Id`, publishes it to MDC key `request_id`, and exposes it as
   servlet attribute `muxai.requestId`. Logback's pattern in
   `logback-spring.xml` renders it as `rid=...` in every log line.
2. `ApiKeyAuthFilter` extracts the `Authorization: Bearer ...` token, resolves
   it to an `AppPrincipal(appId)` against the current `ConfigRuntime` snapshot,
   and stores it in the Spring Security context. Skips `PublicPaths.PATTERNS`.
3. `RateLimitFilter` → `RateLimiter.tryAcquire(appId)` is a per-app token
   bucket (`capacity = rate-limit-per-min`, refill = limit/60s). Returns 429
   with `Retry-After`/`X-RateLimit-*` headers.
4. Controller converts the OpenAI-shape DTO to the internal `ChatRequest`,
   runs `PiiRedactor.redact` (inbound-only text scrubbing), consults
   `SemanticCache`, then calls `Router`.
5. `Router` resolves a `RouteDecision` via `RouteMatcher` (first-match-wins
   glob match on `appId` / `model`), builds a primary-then-fallback chain,
   and walks it. Only `ProviderException` where `code.retryable == true`
   triggers a fallback hop; streaming never falls back (would corrupt the
   already-emitted bytes).
6. `LlmProvider` implementations (`OpenAiProvider`, `AnthropicProvider`)
   translate to/from the provider's native wire format and map HTTP/network
   errors to `ProviderException.Code`.

### Internal model is OpenAI-shaped

The `provider.model` package (`ChatRequest`, `ChatMessage`, `ToolCall`, `Tool`,
`ContentPart`, `ChatChunk`, …) is the canonical in-gateway representation and
mirrors OpenAI's wire format exactly. `OpenAiProvider` is essentially a
passthrough; `AnthropicProvider.toAnthropic` / `toOpenAi` / `translateStreamEvent`
do bidirectional translation, including:

- `system` messages → Anthropic top-level `system` field (concatenated with
  `\n\n` if there are multiple)
- Assistant messages with `tool_calls[]` → mixed `text` + `tool_use` blocks
- OpenAI `role: tool` + `tool_call_id` → Anthropic user message with
  `tool_result` block
- Image parts: `image_url` with a `data:` URL becomes
  `{type:image, source:{type:base64, media_type, data}}`; plain URLs become
  `{type:url}`
- `tool_choice`: `"auto"`→`{type:auto}`, `"required"`→`{type:any}`,
  `{type:function,function:{name}}`→`{type:tool, name}`
- Streaming: Anthropic `message_start` / `content_block_*` / `message_delta`
  events are re-emitted as OpenAI `chat.completion.chunk` shapes. `tool_use`
  blocks accumulate their `input_json_delta` partials indexed by block number.

`ChatMessage.content` is typed as `Object` so it round-trips both the plain
string form and the multi-part list form without re-modelling. Use
`contentAsText()` when an adapter needs a flat string.

### Configuration: startup vs. hot-reload

`config/providers.yml` is loaded both at startup (Spring `@ConfigurationProperties`
→ `GatewayProperties`) and at runtime by `ConfigWatcher` (polls mtime every
`muxai.hot-reload.interval-ms`). The important split:

- **Startup-only**: provider definitions (`ProviderRegistry` builds WebClients
  once per backend via `OpenAiProviderFactory` / `AnthropicProviderFactory`;
  connection pools are not hot-swapped).
- **Hot-reloadable via `ConfigRuntime`**: `routes`, `api-keys`, and rate-limit
  quotas. `ConfigRuntime` is the mutable `AtomicReference<GatewayProperties>`
  + listener bus; `RouteMatcher`, `ApiKeyAuthFilter`, and `RateLimiter` read
  `runtime.current()` on every request and rebuild internal state via
  `addListener`. **Any new consumer of hot-reloadable config must follow the
  same pattern** — injecting `GatewayProperties` directly gives you a frozen
  startup snapshot.

`ConfigWatcher.check()` runs the same `ConfigValidator` as startup. A broken
edit logs a warning and leaves the previous config live — it never takes the
process down.

`ConfigValidator` rejects unreachable routes (a specific rule placed after a
catch-all), duplicate provider/api-key ids, and routes referencing undefined
providers. It fails boot instead of producing 404s at request time.

### Provider adapter contract

Add a new backend type by:

1. Implement `LlmProvider` (methods: `id`, `type`, `supports`, `capabilities`,
   `chat`, and optionally `chatStream` / `embed` / `ocr` — the defaults return
   `UNSUPPORTED`).
2. Map upstream HTTP errors to `ProviderException.Code` in a `mapErrorStatus`
   helper. The `retryable` flag on the code drives router fallback behaviour:
   `RATE_LIMITED`/`PROVIDER_ERROR`/`TIMEOUT`/`NETWORK_ERROR` are retryable;
   `INVALID_REQUEST`/`AUTH_FAILED`/`UNSUPPORTED` are terminal.
3. Add a factory (e.g. `FooProviderFactory`) that builds the per-backend
   `WebClient` with base URL, auth header, and any required default headers.
4. Register the type in `ProviderRegistry.providers(...)` switch and add the
   string to `ConfigValidator.SUPPORTED_TYPES`.

OpenAI-compatible backends (Ollama, vLLM, Groq, Together, Mistral, …) don't
need a new adapter — set `type: openai` with their `base-url` in
`providers.yml`. Only genuine wire-format differences warrant a new class.

### Streaming

`ChatController.chat` branches on `body.stream()` into `streamResponse` which
returns a Spring MVC `SseEmitter` (180s timeout matches
`server.tomcat.connection-timeout`). The flux is subscribed on
`Schedulers.boundedElastic()` because Tomcat's request thread has already
been released by the time chunks arrive. Errors mid-stream emit a final
`event: error` frame + `data: [DONE]`; the router never falls back once bytes
have been written.

### Feature toggles — all off by default

`application.yml` ships with `muxai.pii.enabled`, `muxai.cache.enabled`,
`muxai.hot-reload.enabled` all `false`. Every feature gates on its own
property so a Phase 1 deployment upgrading to 1.0.0 has zero behavioural
drift. Preserve this default-off stance when adding new features.

`SemanticCache` is exact-match SHA-256 keyed on
`(model, messages, temperature, top_p, max_tokens, stop)`. Streaming, tool
calls, and `temperature > max-cacheable-temperature` (default 0.0) bypass the
cache entirely — see `cacheable()`.

### Rate limiting is pluggable

`RateLimiter.Backend` is an interface with `tryConsume` / `rescale` / `evict`.
Only `InMemoryBackend` ships; the `muxai.rate-limit.backend` switch is wired
so a Redis-backed bucket can swap in for multi-replica quota coordination
without changing callers. Until then, multi-replica deployments should treat
"memory" as per-replica.

### Security + public paths

`SecurityConfig` is stateless (no sessions), disables CSRF/form-login, and
chains `ApiKeyAuthFilter` before `UsernamePasswordAuthenticationFilter`, then
`RateLimitFilter` after it. Both filters are registered via Spring Security
**only** — the `FilterRegistrationBean`s with `setEnabled(false)` exist to
stop Spring Boot from *also* registering them as servlet-container filters
(which would double-execute them and collide with `ErrorPageFilter`).

`PublicPaths.PATTERNS` is the single source of truth for unauthenticated
routes. It is consumed by `SecurityConfig` (permitAll), `ApiKeyAuthFilter`
(`shouldNotFilter`), and `RateLimitFilter`. Add any new unauthenticated path
here rather than in each consumer.

### Observability

- Structured log line per request is emitted by `RequestMetrics.recordSuccess`
  / `recordFailure` / `recordStreamSuccess`. Fields are `key=value` pairs
  (`request_id`, `app_id`, `endpoint`, `model_requested`, `route_matched`,
  `provider_attempted`, `provider_succeeded`, `model_actual`, `latency_ms`,
  `prompt_tokens`, `completion_tokens`, `outcome`, `error_code`). Don't break
  the field order/shape without updating downstream log-aggregator parsers.
- Prometheus metrics: `muxai_request_total`, `muxai_provider_call` (timer
  with `app_id`/`provider_id`/`outcome` tags), `muxai_tokens_total`,
  `muxai_cache_hit_total`/`_miss_total`, `muxai_pii_redacted_total`,
  `muxai_request_rate_limited_total`.
- `ProvidersHealthIndicator` exposes provider inventory under
  `/actuator/health` and is included in the readiness probe group.

## Conventions

- **Records everywhere**: DTOs, config properties, and most value types are
  Java records. Provider-wire DTOs live as private/package-private nested
  types inside their adapter (see `AnthropicProvider.AnthropicMessagesRequest`)
  so the wire format is co-located with its translator and doesn't leak.
- **Reactor on the outbound side, blocking on the inbound side**: controllers
  call `Mono.block(Duration.ofSeconds(120))` because Spring MVC is servlet-based.
  Streaming is the one place where the Flux is subscribed directly on
  `Schedulers.boundedElastic()`.
- **Virtual threads are enabled** (`spring.threads.virtual.enabled: true`) —
  don't pin them with `synchronized` around long I/O.
- **Logs are written via SLF4J only** and must use parameter placeholders
  (`log.info("... {} {}", a, b)`) so MDC stays intact.
- **Never log raw request bodies** — they contain prompts which may include
  customer PII even when redaction is on (PII runs after logging decisions
  upstream in some paths).

## Git workflow

Active development branch for this session: `claude/add-claude-documentation-qldcq`.
Push there; PRs merge into `main`. Always open PRs as draft.
