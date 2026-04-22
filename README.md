# MuxAI Gateway

Provider-agnostic LLM gateway. Clients speak the OpenAI Chat Completions API;
MuxAI routes each request to whichever backend (OpenAI, Anthropic, Ollama, vLLM,
any OpenAI-compatible endpoint) a YAML file tells it to use.

**Swap provider by editing a YAML file — client apps never change.**

## Status

Phase 1 MVP + rate-limit enforcement + production hardening:

- `POST /v1/chat/completions` (non-streaming)
- `POST /v1/embeddings`
- `POST /v1/ocr` (image → text; routed via model name, e.g. `typhoon-ocr`)
- `GET  /v1/models`
- Router with primary + fallback chain; first-match-wins
- Provider adapters: OpenAI, Anthropic (request/response translation), and
  any OpenAI-compatible endpoint (use `type: openai` with your own `base-url`)
- Per-app API keys (Bearer token)
- **Per-app rate limiting** — token-bucket keyed by `app-id`, driven by
  `rate-limit-per-min` in `providers.yml`. Responses include
  `X-RateLimit-Limit` / `X-RateLimit-Remaining`; 429s include `Retry-After`.
- **Startup config validation** — duplicate provider ids, undefined fallback
  providers, unreachable routes, and missing api-keys all fail the boot
  (loud crash, no silent degradation).
- **Request correlation ids** — every response carries `X-Request-Id`
  (generated or echoed from the inbound header) and the same id prefixes
  every log line via MDC (`rid=...`).
- **Graceful shutdown** — SIGTERM drains in-flight requests for up to 30s.
- Prometheus metrics at `/actuator/prometheus`
- Kubernetes-style liveness / readiness probes at `/actuator/health/{liveness,readiness}`
- Swagger UI at `/swagger-ui.html`
- Admin UI at `/admin/` (read-only dashboard + playground)

Not yet implemented: streaming, config hot reload, Redis, semantic
cache, PII redaction, tool calling, vision.

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

# 4. Unauthenticated requests are rejected
curl -i -X POST http://localhost:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{"model":"smart","messages":[{"role":"user","content":"hi"}]}'
# -> HTTP/1.1 401

# 5. Authenticated chat completion
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

# 6a. OCR (defaults to model "typhoon-ocr"; accepts data: URI, https URL, or raw base64)
curl -X POST http://localhost:8080/v1/ocr \
  -H "Authorization: Bearer mgw_test_devkey_DO_NOT_USE_IN_PROD" \
  -H "Content-Type: application/json" \
  -d '{
        "model": "typhoon-ocr",
        "image": "data:image/png;base64,iVBORw0KGgo...",
        "prompt": "Extract all text as Markdown"
      }'

# 6. List configured models
curl http://localhost:8080/v1/models \
  -H "Authorization: Bearer mgw_test_devkey_DO_NOT_USE_IN_PROD"

# 7. Metrics
curl http://localhost:8080/actuator/prometheus | head

# 8. Swagger UI
open http://localhost:8080/swagger-ui.html

# 9. Admin UI (paste an API key, then explore providers/routes/models + playground)
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

## Rate limiting

Each entry in `api-keys` may set `rate-limit-per-min`. The gateway keeps a
per-`app-id` token bucket (capacity = the configured limit, refilled at
`limit / 60s`) and rejects excess requests with HTTP 429:

```json
{ "error": { "message": "Rate limit exceeded: 60 requests/min for app 'hr-app'",
             "type": "rate_limit_exceeded", "code": "RATE_LIMITED" } }
```

Every authenticated response also carries `X-RateLimit-Limit` and
`X-RateLimit-Remaining`; 429s add `Retry-After` (seconds). Omit the field,
or set it to `0` / negative, to disable limiting for that app.

State is in-memory — adequate for a single gateway instance. Multi-replica
deployments should swap in a shared backend (Redis) before relying on quotas.

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
   - Liveness: `GET /actuator/health/liveness` — is the JVM alive?
   - Readiness: `GET /actuator/health/readiness` — is at least one provider
     registered? (This group includes the custom `providers` indicator.)
   - Health: `GET /actuator/health` — full component breakdown.
5. **Scrape metrics**: `GET /actuator/prometheus`. In a hostile network, put
   management endpoints behind a separate internal listener or NetworkPolicy —
   `PublicPaths.java` currently whitelists them.
6. **Capture request ids**: log aggregators should key on the `rid=` column
   (logback) or the `X-Request-Id` response header. Callers supplying their
   own ids are respected as long as the value is ≤128 chars and
   printable-ASCII; anything else is replaced with a fresh UUID.
7. **Single-replica rate limiting**: the token bucket is in-memory. If you
   scale out, either pin apps to replicas or swap in a Redis-backed
   `RateLimiter` before trusting the quotas.
