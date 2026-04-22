# MuxAI Gateway

Provider-agnostic LLM gateway. Clients speak the OpenAI Chat Completions API;
MuxAI routes each request to whichever backend (OpenAI, Anthropic, Ollama, vLLM,
any OpenAI-compatible endpoint) a YAML file tells it to use.

**Swap provider by editing a YAML file — client apps never change.**

## Status

Phase 1 MVP:

- `POST /v1/chat/completions` (non-streaming)
- `POST /v1/embeddings`
- `GET  /v1/models`
- Router with primary + fallback chain; first-match-wins
- Provider adapters: OpenAI, Anthropic (request/response translation), and
  any OpenAI-compatible endpoint (use `type: openai` with your own `base-url`)
- Per-app API keys (Bearer token)
- Prometheus metrics at `/actuator/prometheus`
- Swagger UI at `/swagger-ui.html`
- Admin UI at `/admin/` (read-only dashboard + playground)

Not yet implemented: streaming, config hot reload, Redis, semantic
cache, PII redaction, tool calling, vision, rate-limit enforcement.

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

## Adding an OpenAI-compatible backend

Ollama, vLLM, LocalAI, Groq, Together, DeepSeek, Mistral — they all accept
the same JSON that OpenAI does, so they all use `type: openai`:

```yaml
- id: ollama-local
  type: openai
  base-url: http://localhost:11434/v1
  api-key: not-needed
  models: [llama3.3, qwen2.5-coder]
```

## Docker

```bash
docker build -t muxai-gateway .
docker run -p 8080:8080 \
  -e OPENAI_API_KEY=sk-... \
  -v $(pwd)/config:/app/config \
  muxai-gateway
```
