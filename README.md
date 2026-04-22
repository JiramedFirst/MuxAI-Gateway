# MuxAI Gateway

Provider-agnostic LLM gateway. Clients speak the OpenAI Chat Completions API;
MuxAI routes each request to whichever backend (OpenAI, Anthropic, Ollama, vLLM,
any OpenAI-compatible endpoint) a YAML file tells it to use.

**Swap provider by editing a YAML file — client apps never change.**

## Status

Full version (1.0.0). Phase 1 features plus the long tail:

- `POST /v1/chat/completions` (blocking **and** streaming via SSE)
- `POST /v1/embeddings`
- `POST /v1/ocr` (image → text; routed via model name, e.g. `typhoon-ocr`)
- `GET  /v1/models`
- **Tool calling** (`tools` / `tool_choice` / `tool_calls`) — OpenAI passthrough,
  Anthropic `tool_use` ↔ OpenAI-shape translation both for request and response
- **Vision / multimodal content parts** — `content: [{type:text,...},{type:image_url,...}]`
  passes through OpenAI-compatible backends unchanged and converts to Anthropic
  `image` blocks (base64 or URL source) for Claude models
- **Streaming (SSE)** — token-by-token delivery with automatic translation of
  Anthropic `message_*` / `content_block_*` events into OpenAI chunk shape;
  streamed requests bypass fallback (garbled mid-stream failover is worse than
  a clean error)
- **PII redaction** — regex-based inbound filter with Luhn-validated credit card
  detection, email, phone, SSN, and IPv4; each toggle is independent; redaction
  preserves the shape of the original with markers like `[REDACTED_EMAIL]` so the
  model can still reason structurally. Off by default.
- **Semantic cache** — exact-match Caffeine cache for deterministic chat
  requests (`temperature <= max-cacheable-temperature`, no tools, non-streaming),
  keyed on a SHA-256 of the normalized request; cache hits skip the provider
  round-trip entirely. Off by default.
- **Config hot reload** — polls `providers.yml` mtime and atomically swaps
  routes, API keys, and rate-limit quotas without restart. Provider-structure
  changes (adding backends, rotating URLs) still require a restart because
  hot-swapping WebClient pools is out of scope. Off by default.
- **Pluggable rate-limit backend** — in-memory token bucket ships today; the
  `muxai.rate-limit.backend` switch and the `Backend` interface are wired so a
  shared Redis-backed bucket for multi-replica deployments is a drop-in
  replacement (not included in the 1.0.0 release jar).
- Router with primary + fallback chain; first-match-wins
- Provider adapters: OpenAI, Anthropic (request/response translation), and
  any OpenAI-compatible endpoint (use `type: openai` with your own `base-url`)
- Per-app API keys (Bearer token)
- Per-app rate limiting (token-bucket, keyed by `app-id`)
- Startup config validation — duplicate ids, unreachable routes, missing keys
- Request correlation ids — `X-Request-Id` propagated to logs via MDC
- Graceful shutdown — SIGTERM drains in-flight requests for up to 30 s
- Prometheus metrics at `/actuator/prometheus`
- Kubernetes-style liveness/readiness probes
- Swagger UI at `/swagger-ui.html`
- Admin UI at `/admin/`

## Quickstart

```bash
# 1. Build and test
mvn clean verify

# 2. Run (reads ./config/providers.yml)
export OPENAI_API_KEY=sk-...
export ANTHROPIC_API_KEY=sk-ant-...
mvn spring-boot:run

# 3. Health
curl http://localhost:8080/actuator/health

# 4. Authenticated chat completion
curl -X POST http://localhost:8080/v1/chat/completions \
  -H "Authorization: Bearer mgw_test_devkey_DO_NOT_USE_IN_PROD" \
  -H "Content-Type: application/json" \
  -d '{
        "model": "smart",
        "messages": [
          {"role":"system","content":"You are helpful."},
          {"role":"user","content":"Hello"}
        ]
      }'

# 5. Streaming — add "stream": true
curl -N -X POST http://localhost:8080/v1/chat/completions \
  -H "Authorization: Bearer mgw_test_devkey_DO_NOT_USE_IN_PROD" \
  -H "Content-Type: application/json" \
  -d '{"model":"smart","messages":[{"role":"user","content":"Hi"}],"stream":true}'
# → data: {"id":"...","choices":[{"delta":{"content":"H"}}]} (×N)
# → data: [DONE]

# 6. Tool calling
curl -X POST http://localhost:8080/v1/chat/completions \
  -H "Authorization: Bearer mgw_test_devkey_DO_NOT_USE_IN_PROD" \
  -H "Content-Type: application/json" \
  -d '{
        "model": "smart",
        "messages":[{"role":"user","content":"What is the weather in Paris?"}],
        "tools":[{
          "type":"function",
          "function":{
            "name":"get_weather",
            "description":"Return the current weather for a city.",
            "parameters":{"type":"object","properties":{"city":{"type":"string"}},"required":["city"]}
          }
        }],
        "tool_choice":"auto"
      }'

# 7. Vision (multi-part content)
curl -X POST http://localhost:8080/v1/chat/completions \
  -H "Authorization: Bearer mgw_test_devkey_DO_NOT_USE_IN_PROD" \
  -H "Content-Type: application/json" \
  -d '{
        "model":"claude-sonnet-4-6",
        "messages":[{"role":"user","content":[
          {"type":"text","text":"What is in this image?"},
          {"type":"image_url","image_url":{"url":"https://example.com/cat.jpg"}}
        ]}]
      }'

# 8. OCR (defaults to model "typhoon-ocr")
curl -X POST http://localhost:8080/v1/ocr \
  -H "Authorization: Bearer mgw_test_devkey_DO_NOT_USE_IN_PROD" \
  -H "Content-Type: application/json" \
  -d '{"model":"typhoon-ocr","image":"data:image/png;base64,iVBORw0KGgo..."}'

# 9. List configured models
curl http://localhost:8080/v1/models \
  -H "Authorization: Bearer mgw_test_devkey_DO_NOT_USE_IN_PROD"

# 10. Metrics / Swagger / Admin
curl http://localhost:8080/actuator/prometheus | head
open http://localhost:8080/swagger-ui.html
open http://localhost:8080/admin/
```

## Configuration

All routing lives in `./config/providers.yml`. See the file itself for the
complete example — the short version:

```yaml
muxai:
  providers:
    - id: openai-main
      type: openai
      base-url: https://api.openai.com/v1
      api-key: ${OPENAI_API_KEY:}
      models: [gpt-4o, gpt-4o-mini]

    - id: anthropic-main
      type: anthropic
      base-url: https://api.anthropic.com/v1
      api-key: ${ANTHROPIC_API_KEY:}
      models: [claude-opus-4-7, claude-sonnet-4-6]

  routes:
    - match: {model: "smart"}
      primary: {provider: openai-main, model: gpt-4o}
      fallback:
        - {provider: anthropic-main, model: claude-sonnet-4-6}

    - match: {model: "claude-*"}
      primary: {provider: anthropic-main}

    - match: {}        # catch-all
      primary: {provider: openai-main}

  api-keys:
    - key: mgw_test_devkey_DO_NOT_USE_IN_PROD
      app-id: dev
      rate-limit-per-min: 1000
```

Routes are evaluated top-to-bottom; the first one that matches wins. Put
specific rules before general ones.

## Feature toggles (application.yml)

Every full-version feature is off by default so Phase 1 deployments upgrade
with zero behavioural drift. Enable what you need:

```yaml
muxai:
  pii:
    enabled: true          # master switch; individual kinds below opt out
    email: true
    phone: true
    credit-card: true
    ssn: true
    ipv4: false

  cache:
    enabled: true
    max-entries: 10000
    ttl-seconds: 3600
    max-cacheable-temperature: 0.0   # only deterministic requests are cached

  hot-reload:
    enabled: true
    path: ./config/providers.yml
    interval-ms: 5000

  rate-limit:
    backend: memory        # "redis" reserved for a future release
```

## Streaming

Send `"stream": true` in the chat request body. The response has
`Content-Type: text/event-stream` and one `data:` frame per chunk, terminated
with `data: [DONE]`. Failure inside the stream emits a final `event: error`
frame with an OpenAI-shaped `{"error":{...}}` body.

Streamed requests do **not** fall back to a secondary provider — once bytes
have been written to the client, retrying would corrupt the stream. Configure
your primary-for-streaming providers with this in mind.

## Tool calling

The request shape matches the OpenAI wire format exactly:

```json
{
  "tools": [{
    "type": "function",
    "function": {"name": "...", "description": "...", "parameters": { ...schema... }}
  }],
  "tool_choice": "auto"
}
```

For Anthropic routes, `tools` become Anthropic tool definitions, the response
is translated so `tool_use` blocks surface as `message.tool_calls[]`,
`tool_choice: "auto"` maps to Anthropic's `{type:"auto"}`, `"required"` to
`{type:"any"}`, and `{"type":"function","function":{"name":"X"}}` to
`{"type":"tool","name":"X"}`. OpenAI-compatible backends get passthrough.

## Vision

`content` may be a plain string (today's shape) or an array of parts:

```json
{"role":"user","content":[
  {"type":"text","text":"What is in this image?"},
  {"type":"image_url","image_url":{"url":"https://example.com/cat.jpg"}},
  {"type":"image_url","image_url":{"url":"data:image/jpeg;base64,..."}}
]}
```

Pass-through to OpenAI-compatible backends. For Anthropic, image parts become
`image` blocks with a `source.type` of `base64` (for data URIs) or `url`.

## PII redaction

Inbound only — responses are never rewritten. When enabled, user/system/tool
text is scanned for matches and replaced with structural tokens:

- `[REDACTED_EMAIL]` — RFC-ish email regex
- `[REDACTED_PHONE]` — international phone shapes
- `[REDACTED_CARD]` — 13–19 digit runs that pass Luhn
- `[REDACTED_SSN]` — `NNN-NN-NNNN`
- `[REDACTED_IP]` — IPv4 dotted quads

Redaction counts are emitted as `muxai_pii_redacted_total` Prometheus counters
with `kind` and `app_id` labels.

## Semantic cache

Today the cache is keyed on a SHA-256 of the deterministic slice of the
request: model, messages, temperature, top_p, max_tokens, stop. Temperature
above `max-cacheable-temperature` (default 0.0), `tools`, and streaming requests
bypass the cache. Hits are recorded at `muxai_cache_hit_total`.

The class is named `SemanticCache` to signal intent — a future release can
swap the hash-keying for an embedding-cosine similarity lookup behind the
same API without touching callers.

## Config hot reload

Polls `providers.yml` mtime. On change: re-parses with Spring's `Binder`, runs
the same `ConfigValidator` that gates startup, and — if valid — atomically
replaces the current config. Consumers (`RouteMatcher`, `ApiKeyAuthFilter`,
`RateLimiter`) pick up the swap on the next request.

A broken edit leaves the old config live and logs a warning. Provider-list
changes (new backend id, changed `base-url`) require a restart.

## Rate limiting

Each entry in `api-keys` may set `rate-limit-per-min`. The gateway keeps a
per-`app-id` token bucket (capacity = the configured limit, refilled at
`limit / 60s`) and rejects excess requests with HTTP 429:

```json
{ "error": { "message": "Rate limit exceeded: 60 requests/min for app 'hr-app'",
             "type": "rate_limit_exceeded", "code": "RATE_LIMITED" } }
```

Responses carry `X-RateLimit-Limit` / `X-RateLimit-Remaining`; 429s add
`Retry-After`. Omit the field, or set it to 0/negative, to disable limiting
for that app. Hot reload rescales existing buckets in place and evicts
buckets for removed apps.

The `muxai.rate-limit.backend` switch selects between the bundled in-memory
backend and a future Redis-backed one. Multi-replica deployments that need
cross-node quota coordination should treat "memory" as per-replica until the
Redis backend lands.

## Adding an OpenAI-compatible backend

Ollama, vLLM, LocalAI, Groq, Together, DeepSeek, Mistral, OpenTyphoon — they
all accept the same JSON that OpenAI does, so they all use `type: openai`:

```yaml
- id: ollama-local
  type: openai
  base-url: http://localhost:11434/v1
  api-key: not-needed
  models: [llama3.3, qwen2.5-coder]
```

## Docker

The image runs as UID 1001 (`muxai`), exposes an `HEALTHCHECK` against
`/actuator/health`, and sets JVM flags that honour container memory limits
(`-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError`).

```bash
docker build -t muxai-gateway .
docker run -p 8080:8080 \
  -e SPRING_PROFILES_ACTIVE=prod \
  -e OPENAI_API_KEY=sk-... \
  -v $(pwd)/config:/app/config \
  muxai-gateway
```

## Production deployment

1. **Activate the prod profile**: set `SPRING_PROFILES_ACTIVE=prod`. This
   turns off the Spring banner, collapses logging to WARN/INFO, and hides
   stack traces from error responses. See `application-prod.yml`.
2. **Inject secrets via env, not YAML**: `providers.yml` references
   `${OPENAI_API_KEY:}` / `${ANTHROPIC_API_KEY:}` and the same substitution
   works for API keys (`key: ${MGW_PROD_KEY}`). Never commit real keys.
3. **Replace the dev API key**: the shipped `mgw_test_devkey_DO_NOT_USE_IN_PROD`
   is explicitly named to fail audits — swap it out for a real key sourced
   from your secrets manager before deploying.
4. **Wire up probes**:
   - Liveness: `GET /actuator/health/liveness`
   - Readiness: `GET /actuator/health/readiness`
   - Health: `GET /actuator/health`
5. **Scrape metrics**: `GET /actuator/prometheus`. In a hostile network, put
   management endpoints behind a separate internal listener or NetworkPolicy —
   `PublicPaths.java` currently whitelists them.
6. **Capture request ids**: log aggregators should key on the `rid=` column
   (logback) or the `X-Request-Id` response header.
7. **Multi-replica rate limiting**: the token bucket is in-memory. If you
   scale out, either pin apps to replicas or swap in the Redis backend once
   it ships.
