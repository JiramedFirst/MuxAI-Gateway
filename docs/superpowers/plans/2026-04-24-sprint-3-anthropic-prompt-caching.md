# Sprint 3 — Anthropic Prompt Caching Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Enable Anthropic prompt caching in the gateway by preserving per-content-block `cache_control` markers through `toAnthropic` translation and adding the required beta header to the Anthropic WebClient factory.

**Architecture:** Two surgical changes. (1) `AnthropicProvider.translateContentPart` currently returns `Map.of("type", ...)` immutable maps; switch to `LinkedHashMap` and conditionally copy the `cache_control` key when present on the input `Map` (the Jackson-deserialised form that real requests arrive as). (2) `AnthropicProviderFactory.create` adds `defaultHeader("anthropic-beta", "prompt-caching-2024-07-31")` unconditionally — there is no per-provider opt-out, and the header is a no-op for non-caching requests. System-level caching is out of scope: today the gateway concatenates system messages into a single string, so a future plan will restructure system to a block array.

**Tech Stack:** Java 21, Jackson (`Map<String, Object>` blocks), JUnit 5 + AssertJ, WireMock for header verification.

---

## File Structure

- **Create** — none
- **Modify**
  - `src/main/java/com/muxai/gateway/provider/anthropic/AnthropicProvider.java:198-223` — swap `Map.of(...)` for `LinkedHashMap` and pass through `cache_control`.
  - `src/main/java/com/muxai/gateway/provider/anthropic/AnthropicProviderFactory.java:22-30` — add the beta header.
  - `CLAUDE.md` — document the new behavior + known limitation on system-level caching.
- **Test**
  - `src/test/java/com/muxai/gateway/provider/anthropic/AnthropicTranslationTest.java` — new tests for cache_control passthrough on text and image blocks.
  - `src/test/java/com/muxai/gateway/provider/anthropic/AnthropicProviderTest.java` — new test for outbound beta header.

---

### Task 1: Preserve `cache_control` on text + image content blocks

**Files:**
- Modify: `src/main/java/com/muxai/gateway/provider/anthropic/AnthropicProvider.java`
- Test: `src/test/java/com/muxai/gateway/provider/anthropic/AnthropicTranslationTest.java`

- [ ] **Step 1: Write the failing tests**

Append the following tests to the existing `AnthropicTranslationTest` class (inside its body, before the closing `}`):

```java
@Test
void textContentPartPreservesCacheControl() {
    Map<String, Object> part = new LinkedHashMap<>();
    part.put("type", "text");
    part.put("text", "Long system-like prompt");
    part.put("cache_control", Map.of("type", "ephemeral"));

    ChatRequest r = new ChatRequest(
            "claude-sonnet-4-6",
            List.of(new ChatMessage("user", List.of(part))),
            null, null, 100, null, null, null, null);

    AnthropicProvider.AnthropicMessagesRequest body = provider.toAnthropic(r, false);

    List<?> blocks = (List<?>) body.messages().get(0).content();
    Map<?, ?> textBlock = (Map<?, ?>) blocks.get(0);
    assertThat(textBlock.get("type")).isEqualTo("text");
    assertThat(textBlock.get("text")).isEqualTo("Long system-like prompt");
    assertThat(textBlock.get("cache_control")).isEqualTo(Map.of("type", "ephemeral"));
}

@Test
void textContentPartWithoutCacheControlHasNoCacheControlKey() {
    Map<String, Object> part = Map.of("type", "text", "text", "hi");

    ChatRequest r = new ChatRequest(
            "claude-sonnet-4-6",
            List.of(new ChatMessage("user", List.of(part))),
            null, null, 100, null, null, null, null);

    AnthropicProvider.AnthropicMessagesRequest body = provider.toAnthropic(r, false);
    List<?> blocks = (List<?>) body.messages().get(0).content();
    Map<?, ?> textBlock = (Map<?, ?>) blocks.get(0);
    assertThat(textBlock).doesNotContainKey("cache_control");
}

@Test
void imageContentPartPreservesCacheControl() {
    Map<String, Object> part = new LinkedHashMap<>();
    part.put("type", "image_url");
    part.put("image_url", Map.of("url", "https://example.com/cat.jpg"));
    part.put("cache_control", Map.of("type", "ephemeral"));

    ChatRequest r = new ChatRequest(
            "claude-sonnet-4-6",
            List.of(new ChatMessage("user", List.of(part))),
            null, null, 100, null, null, null, null);

    AnthropicProvider.AnthropicMessagesRequest body = provider.toAnthropic(r, false);
    List<?> blocks = (List<?>) body.messages().get(0).content();
    Map<?, ?> imageBlock = (Map<?, ?>) blocks.get(0);
    assertThat(imageBlock.get("type")).isEqualTo("image");
    assertThat(imageBlock.get("cache_control")).isEqualTo(Map.of("type", "ephemeral"));
}
```

Add imports if missing at the top of the file:

```java
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -Dtest=AnthropicTranslationTest#textContentPartPreservesCacheControl+AnthropicTranslationTest#imageContentPartPreservesCacheControl test`
Expected: FAIL — current `translateContentPart` returns `Map.of("type", "text", "text", ...)` with no `cache_control` key.

- [ ] **Step 3: Update `translateContentPart`**

Open `src/main/java/com/muxai/gateway/provider/anthropic/AnthropicProvider.java`. Find the entire `translateContentPart` method (currently around lines 198-223):

```java
    private Object translateContentPart(Object part) {
        String type;
        Object textVal = null;
        Object imageUrl = null;
        if (part instanceof Map<?, ?> map) {
            type = map.get("type") instanceof String t ? t : null;
            textVal = map.get("text");
            imageUrl = map.get("image_url");
        } else if (part instanceof ContentPart cp) {
            type = cp.type();
            textVal = cp.text();
            imageUrl = cp.imageUrl();
        } else {
            return null;
        }

        if ("text".equals(type)) {
            return Map.of("type", "text", "text", textVal == null ? "" : textVal.toString());
        }
        if ("image_url".equals(type)) {
            String url = extractImageUrl(imageUrl);
            if (url == null) return null;
            return Map.of("type", "image", "source", imageSourceOf(url));
        }
        return null;
    }
```

Replace it with:

```java
    private Object translateContentPart(Object part) {
        String type;
        Object textVal = null;
        Object imageUrl = null;
        Object cacheControl = null;
        if (part instanceof Map<?, ?> map) {
            type = map.get("type") instanceof String t ? t : null;
            textVal = map.get("text");
            imageUrl = map.get("image_url");
            cacheControl = map.get("cache_control");
        } else if (part instanceof ContentPart cp) {
            type = cp.type();
            textVal = cp.text();
            imageUrl = cp.imageUrl();
        } else {
            return null;
        }

        if ("text".equals(type)) {
            Map<String, Object> block = new LinkedHashMap<>();
            block.put("type", "text");
            block.put("text", textVal == null ? "" : textVal.toString());
            if (cacheControl != null) block.put("cache_control", cacheControl);
            return block;
        }
        if ("image_url".equals(type)) {
            String url = extractImageUrl(imageUrl);
            if (url == null) return null;
            Map<String, Object> block = new LinkedHashMap<>();
            block.put("type", "image");
            block.put("source", imageSourceOf(url));
            if (cacheControl != null) block.put("cache_control", cacheControl);
            return block;
        }
        return null;
    }
```

`LinkedHashMap` is already imported at the top of the file (used elsewhere in the class).

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -Dtest=AnthropicTranslationTest test`
Expected: PASS — all three new tests plus the existing translation suite.

- [ ] **Step 5: Run the full unit suite**

Run: `mvn test`
Expected: all green. No other callers of `translateContentPart` — it's private to `AnthropicProvider`.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/muxai/gateway/provider/anthropic/AnthropicProvider.java \
        src/test/java/com/muxai/gateway/provider/anthropic/AnthropicTranslationTest.java
git commit -m "feat(anthropic): preserve cache_control on text and image content blocks"
```

---

### Task 2: Add `anthropic-beta` default header in factory

**Files:**
- Modify: `src/main/java/com/muxai/gateway/provider/anthropic/AnthropicProviderFactory.java`
- Test: `src/test/java/com/muxai/gateway/provider/anthropic/AnthropicProviderTest.java`

- [ ] **Step 1: Write the failing test**

Read the top of `src/test/java/com/muxai/gateway/provider/anthropic/AnthropicProviderTest.java` to understand the WireMock pattern already in place:

```bash
head -60 src/test/java/com/muxai/gateway/provider/anthropic/AnthropicProviderTest.java
```

Append the following test to that class (inside its body, before the closing `}`):

```java
@Test
void outboundRequestsCarryPromptCachingBetaHeader() {
    stubFor(post(urlEqualTo("/messages"))
            .willReturn(okJson("""
                {"id":"msg_x","type":"message","role":"assistant","model":"claude-sonnet-4-6",
                 "content":[{"type":"text","text":"ok"}],
                 "stop_reason":"end_turn",
                 "usage":{"input_tokens":1,"output_tokens":1}}
                """)));

    ChatRequest r = new ChatRequest(
            "claude-sonnet-4-6",
            List.of(new ChatMessage("user", "hi")),
            null, null, 100, null, null, null, null);

    provider.chat(r).block(Duration.ofSeconds(5));

    verify(postRequestedFor(urlEqualTo("/messages"))
            .withHeader("anthropic-beta", equalTo("prompt-caching-2024-07-31")));
}
```

Add imports at the top of the file if absent:

```java
import java.time.Duration;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -Dtest=AnthropicProviderTest#outboundRequestsCarryPromptCachingBetaHeader test`
Expected: FAIL — WireMock reports the header is missing from the request.

- [ ] **Step 3: Update the factory to set the header**

Open `src/main/java/com/muxai/gateway/provider/anthropic/AnthropicProviderFactory.java`. Find:

```java
    public AnthropicProvider create(ProviderProperties props) {
        WebClient.Builder local = builder.clone()
                .baseUrl(props.baseUrl())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader("anthropic-version", "2023-06-01");
        if (props.apiKey() != null && !props.apiKey().isBlank()) {
            local = local.defaultHeader("x-api-key", props.apiKey());
        }
        return new AnthropicProvider(props, local.build(), mapper);
    }
```

Replace with:

```java
    public AnthropicProvider create(ProviderProperties props) {
        WebClient.Builder local = builder.clone()
                .baseUrl(props.baseUrl())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader("anthropic-version", "2023-06-01")
                .defaultHeader("anthropic-beta", "prompt-caching-2024-07-31");
        if (props.apiKey() != null && !props.apiKey().isBlank()) {
            local = local.defaultHeader("x-api-key", props.apiKey());
        }
        return new AnthropicProvider(props, local.build(), mapper);
    }
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn -Dtest=AnthropicProviderTest#outboundRequestsCarryPromptCachingBetaHeader test`
Expected: PASS — WireMock confirms the header is present.

- [ ] **Step 5: Run the full verify suite**

Run: `mvn verify`
Expected: all unit and integration tests pass.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/muxai/gateway/provider/anthropic/AnthropicProviderFactory.java \
        src/test/java/com/muxai/gateway/provider/anthropic/AnthropicProviderTest.java
git commit -m "feat(anthropic): add prompt-caching-2024-07-31 beta header default"
```

---

### Task 3: Document the new behavior in CLAUDE.md

**Files:**
- Modify: `CLAUDE.md`

- [ ] **Step 1: Update the Anthropic translation bullet list**

Open `CLAUDE.md`. Find the bullet list under "Internal model is OpenAI-shaped" that describes what `AnthropicProvider` translates. After the existing `image_url` bullet, add:

```markdown
- Per-content-block `cache_control`: the inbound part's `cache_control` key
  (e.g. `{"type":"ephemeral"}`) is copied verbatim onto the translated
  Anthropic block so prompt caching works on both `text` and `image`
  blocks. Factory attaches the required `anthropic-beta: prompt-caching-2024-07-31`
  default header. **System-level caching is NOT yet supported** — the
  gateway still collapses multiple `system` messages into a single string
  field, which strips any `cache_control`. A future plan will restructure
  system into the `[{type: "text", text, cache_control}]` array form.
```

- [ ] **Step 2: Commit**

```bash
git add CLAUDE.md
git commit -m "docs: note Anthropic cache_control passthrough + beta header"
```

---

## Self-Review Checklist

- **Spec coverage:** `cache_control` preserved per-content-block (Task 1); `anthropic-beta: prompt-caching-2024-07-31` header added (Task 2). ✓
- **Placeholder scan:** all code blocks are concrete; no "TBD" / "similar to Task N" references. ✓
- **Type consistency:** `Map<String, Object>` blocks built with `LinkedHashMap` in both text and image branches; `cache_control` key name is identical across tests and implementation. ✓
- **Known limitation surfaced:** system-level caching is called out in Task 3's doc update as a scoped-out future change, not silently omitted. ✓
- **No breakage:** existing tests for `translateContentPart` still pass because the new code preserves prior output for inputs without `cache_control` (a `LinkedHashMap` with the same entries is `equals` to a `Map.of(...)` with the same entries). ✓

---

## Execution Handoff

**Plan complete and saved to `docs/superpowers/plans/2026-04-24-sprint-3-anthropic-prompt-caching.md`.**

Two execution options:
1. **Subagent-Driven (recommended)** — dispatch a fresh subagent per task, review between tasks.
2. **Inline Execution** — execute tasks in this session using `superpowers:executing-plans`, batched with checkpoints.

Which approach?
