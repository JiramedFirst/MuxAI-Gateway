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
   it to a full `ApiKey` via the current `ConfigRuntime` snapshot, rejects
   expired keys (`ApiKey.expiresAt < now`) with 401, and stamps an
   `AppPrincipal(appId, apiKey)` into the Spring Security context with
   `ROLE_ADMIN` or `ROLE_APP` derived from `ApiKey.role` (default `app`).
   Skips `PublicPaths.PATTERNS`.
3. `RateLimitFilter` → `RateLimiter.tryAcquire(appId)` is a per-app token
   bucket (`capacity = rate-limit-per-min`, refill = limit/60s). Returns 429
   with `Retry-After`/`X-RateLimit-*` headers.
4. Controller calls `ModelScopeGuard.check(principal, body.model())` before
   any routing — if `ApiKey.allowedModels` is non-empty and the requested
   model isn't in it, throws `ModelAccessDeniedException` → 403 (OpenAI error
   shape `permission_error` / `MODEL_NOT_ALLOWED`). Then converts the
   OpenAI-shape DTO to the internal `ChatRequest`, runs `PiiRedactor.redact`
   (inbound-only text scrubbing), consults `SemanticCache`, then calls `Router`.
5. `Router` resolves a `RouteDecision` via `RouteMatcher` (first-match-wins
   glob match on `appId` / `model`), builds a primary-then-fallback chain,
   and walks it. Only `ProviderException` where `code.retryable == true`
   triggers a fallback hop; streaming never falls back (would corrupt the
   already-emitted bytes).
6. `LlmProvider` implementations (`OpenAiProvider`, `AnthropicProvider`)
   translate to/from the provider's native wire format and map HTTP/network
   errors to `ProviderException.Code`.

`EmbeddingController` (`/v1/embeddings`), `OcrController` (`/v1/ocr`), and
`ModelsController` (`/v1/models`) share the same auth + rate-limit prefix but
skip PII/cache (neither is cacheable today, and OCR input is binary). Add new
endpoints by composing the same filter chain — don't bypass
`ApiKeyAuthFilter`. `GlobalExceptionHandler` converts every thrown exception
to an OpenAI-shaped `{error:{message,type,code}}` body so clients written
against OpenAI SDKs don't need gateway-specific error paths.

`AdminController` exposes `GET /admin/api/overview` (providers, routes,
api-keys — all with secrets masked via `AdminController.mask`) and
`POST /admin/api/keys/rotate` (rotation; see below) for the static dashboard
served from `resources/static/admin/`. **Static admin assets** (`/admin/index.html`,
`/admin/app.js`, `/admin/styles.css`, `/admin/favicon.ico`) are in
`PublicPaths.PATTERNS` because browsers can't send `Authorization` on
`<script src>` / `<link href>` loads. The **REST surface** under
`/admin/api/**` requires `ROLE_ADMIN` (gated in `SecurityConfig`); a key with
`role: app` (the default) gets 403 there. Operators must have at least one
key with `role: admin` in `providers.yml` to use the dashboard. Keep all
secrets masked before serialising.

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

### Configuration: startup vs. hot-reload vs. runtime overlay

Three layers contribute to `runtime.current()`:

- **`config/providers.yml`** (declared source of truth) — loaded at startup
  via Spring `@ConfigurationProperties` → `GatewayProperties` and re-parsed
  by `ConfigWatcher` on mtime change every `muxai.hot-reload.interval-ms`.
- **`config/runtime-keys.yml`** (rotation overlay) — written by the
  `POST /admin/api/keys/rotate` flow; read by `ApiKeyOverlay` and merged into
  `GatewayProperties.apiKeys` on every reload AND once at boot via
  `@PostConstruct` on `ConfigWatcher`. Merge rule: dedupe by Bearer token;
  overlay entries win ties (this is how rotation marks an old key with
  `expiresAt`). Path configurable via `muxai.admin.runtime-keys-path`.
- **`ConfigRuntime`** is the mutable `AtomicReference<GatewayProperties>` +
  listener bus that holds the merged result. `RouteMatcher`,
  `ApiKeyAuthFilter`, and `RateLimiter` read `runtime.current()` on every
  request and rebuild internal state via `addListener`.

The important split:

- **Startup-only**: provider definitions (`ProviderRegistry` builds WebClients
  once per backend via `OpenAiProviderFactory` / `AnthropicProviderFactory`;
  connection pools are not hot-swapped).
- **Hot-reloadable**: `routes`, `api-keys` (including rotation overlay), and
  rate-limit quotas. **Any new consumer of hot-reloadable config must follow
  the listener pattern** — injecting `GatewayProperties` directly gives you a
  frozen startup snapshot that won't see overlay merges either.

`ConfigWatcher.check()` runs the same `ConfigValidator` as startup. A broken
edit logs a warning and leaves the previous config live — it never takes the
process down.

`ConfigValidator` rejects unreachable routes (a specific rule placed after a
catch-all), duplicate provider/api-key ids, routes referencing undefined
providers, unknown roles (must be `admin`/`app`), negative `dailyBudgetUsd`,
and negative `pricing` values. It fails boot instead of producing 404s at
request time.

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

Authorization rules in the chain (order matters):

1. `PublicPaths.PATTERNS` → `permitAll()` — actuator subset, swagger-ui,
   static admin assets, `/error`.
2. `/admin/api/**` → `hasAuthority("ROLE_ADMIN")` — gates the admin REST
   surface even though the static dashboard pages are public.
3. `anyRequest().authenticated()` — everything else needs a valid Bearer key.

`PublicPaths.PATTERNS` is the single source of truth for unauthenticated
routes. It is consumed by `SecurityConfig` (permitAll), `ApiKeyAuthFilter`
(`shouldNotFilter`), and `RateLimitFilter`. Add any new unauthenticated path
here rather than in each consumer.

### API-key scoping, expiry, and rotation

`ApiKey` carries optional fields beyond the original `(key, appId, rateLimitPerMin)`:

- `allowedModels: List<String>` — exact-match ACL enforced by `ModelScopeGuard`
  in Chat/Embedding/OCR controllers; `ModelsController` filters its listing
  to this set. Empty list means "no scope" (unrestricted) so existing keys
  without the field continue to work unchanged.
- `allowedEndpoints: List<String>` — reserved for a future endpoint-level
  guard; not enforced yet.
- `role: String` — `admin` or `app` (default `app`). Drives `ROLE_*` authority
  in `ApiKeyAuthFilter`.
- `expiresAt: Instant` — checked at request time by `ApiKeyAuthFilter.isExpired(now)`.
  Boot does NOT reject expired entries (operators legitimately leave them in
  `providers.yml` until cleanup).
- `dailyBudgetUsd: Double` — reserved for Sprint 2 `BudgetGuard`; not enforced
  yet.

**Rotation flow** (`KeyRotationService` + `ApiKeyOverlay`):

`POST /admin/api/keys/rotate` body `{"key": "<old-token>"}` (admin-only):
1. Find the old `ApiKey` by token.
2. Reject (400) if it's already in rotation (`expiresAt` in the future).
3. Generate a new `mgw_` + 32-hex-char token via `SecureRandom`.
4. Write both entries to `runtime-keys.yml` atomically (`.tmp` + `Files.move`
   with `ATOMIC_MOVE`):
   - Old token gets `expiresAt = now + muxai.admin.rotation-grace-seconds`
     (default 600s) — keeps working until grace expires.
   - New token has no expiry; inherits all other fields from the old key.
5. Call `ConfigWatcher.reloadOverlay()` so both keys are live before the
   response returns.
6. Respond with the new token in plaintext **exactly once** (the only time
   it's retrievable; never logged).

Rotation state survives restart because `ConfigWatcher.@PostConstruct`
applies the overlay before opening the request port.

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
  `muxai_request_rate_limited_total`, `muxai_config_reload_total` (tagged
  `outcome=success|invalid|parse_failed|io_error|listener_error` — alerts
  should fire on anything but `success`).
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

Branch per change; PRs merge into `main`. Always open PRs as draft.

When chaining PRs (e.g. dependent feature work), avoid stacking the bases
(`PR-B → PR-A → main`) — merging the inner PRs into their feature-branch
parents does NOT propagate the changes to `main`. Either keep all PRs
targeting `main` and accept the larger diff, or open a fresh combined PR
once the chain merges into the trunk.

## Roadmap (Sprints 2–5)

Sprints 0 and 1 (schema foundations + admin gating + ACLs + key rotation)
are merged. The remaining roadmap is captured in
`~/.claude/plans/graceful-watching-scott.md`. High-level scope:

- **Sprint 2 — Cost & governance** (~5-7 days)
  - `cost/PricingTable` reading `ProviderProperties.pricing`.
  - `muxai_cost_usd_total{app_id, provider_id, model}` counter in
    `RequestMetrics` (incremented in `recordSuccess` / `recordStreamSuccess`).
  - `cost/BudgetGuard` enforcing per-app `dailyBudgetUsd` (post-hoc — overshoots
    by up to one in-flight request; documented as hard-cap-on-next-request).
    429 with `type: "budget_exceeded"`.
  - `RouteMatcher.findRoute` → `findAllMatching` + new `RouteSelector` strategy
    interface; default `PrimaryFirst`, opt-in `CheapestFirst` via
    `routes[].strategy: cheapest`. Touches both `Router.routeChat` and
    `Router.streamChat`.
  - `PiiRedactor.redactResponse(ChatResponse)` — blocking responses only.
    Streaming-aware outbound scrub deferred (split-across-chunks problem).
    Gated on `muxai.pii.outbound.enabled`.

- **Sprint 3 — Provider feature parity** (~3-4 days)
  - `OpenAiChatRequest` + internal `ChatRequest` get `response_format`,
    `seed`, `stream_options`. OpenAI passes through verbatim; Anthropic drops
    with a logged warning (no shim).
  - `AnthropicProvider.toAnthropic` preserves per-content-block
    `cache_control`; factory adds `anthropic-beta: prompt-caching-2024-07-31`
    default header.
  - `SemanticCache.Backend` interface; current Caffeine impl becomes
    `ExactMatchBackend`. `EmbeddingBackend` deferred until traffic exists to
    tune the similarity threshold.

- **Sprint 4 — Operations** (~4-6 days)
  - `RedisRateLimitBackend` implementing existing `RateLimiter.Backend` (Lua
    `EVAL` for atomic token-bucket consume across replicas).
  - OpenTelemetry: `micrometer-tracing-bridge-otel` +
    `opentelemetry-exporter-otlp`. Logback pattern gets
    `tid=%X{traceId:--} sid=%X{spanId:--}` **appended** after the existing
    `rid=` block (additive — don't reorder; downstream parsers depend on it).
    Provider WebClient factories add an `ExchangeFilterFunction` forwarding
    `traceparent` upstream.
  - `charts/muxai-gateway/` Helm chart, `deploy/k8s/` plain manifests,
    `docker-compose.yml` with Redis service for local multi-replica testing.

- **Sprint 5 — Admin live-tail** (~1-2 days)
  - `RequestTailBuffer` bounded ring (~500 entries, already-redacted log lines).
  - `SseResponseHelper` — extract the SSE pattern from `ChatController` so
    the admin endpoint reuses it.
  - `AdminTailController` (`GET /admin/api/tail`, ROLE_ADMIN) streams new
    entries via SSE.
  - New "Live" tab in `static/admin/` using fetch + ReadableStream (EventSource
    can't carry a Bearer header).

**Cross-cutting design decisions** (already locked in by Sprint 0/1
implementation, applies to all remaining sprints):

1. Admin auth is role-based API keys — not mTLS.
2. Embedding-based cache backend deferred (interface only ships in Sprint 3).
3. Streaming outbound PII deferred to post-v1.
4. Structured outputs are pass-through only; no server-side validation.
5. OTEL Logback changes must be additive.
6. Budget enforcement is post-hoc (overshoots by one in-flight request).
7. Rotation state lives in `runtime-keys.yml` overlay, not embedded in
   `providers.yml`.
