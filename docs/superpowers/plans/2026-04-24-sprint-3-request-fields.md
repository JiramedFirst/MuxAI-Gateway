# Sprint 3 — Request Fields (response_format, seed, stream_options) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add three OpenAI-native request fields — `response_format`, `seed`, `stream_options` — to the inbound DTO and internal model so OpenAI-compatible backends can honour them while Anthropic drops them with a logged warning.

**Architecture:** Three fields are added at the end of the `OpenAiChatRequest` record (HTTP DTO) and the internal `ChatRequest` record. Types are `Object` / `Integer` / `Object` to pass through arbitrary JSON shapes (`response_format` may be `{"type":"json_object"}` or a JSON-schema envelope; `stream_options` is typically `{"include_usage":true}`). A 9-arg convenience constructor on `ChatRequest` preserves back-compat with existing tests. OpenAI adapter requires no changes — Jackson serialises the fields via the record's `@JsonProperty` annotations. Anthropic adapter logs once per field when any are set and omits them from the translated wire body.

**Tech Stack:** Java 21 records, Jackson (`@JsonInclude(NON_NULL)`, `@JsonProperty`), JUnit 5 + AssertJ, WireMock for provider integration tests.

---

## File Structure

- **Create** — none
- **Modify**
  - `src/main/java/com/muxai/gateway/provider/model/ChatRequest.java` — add 3 fields; keep 9-arg convenience; update `withModel`/`withMessages`/`withStream` to forward them.
  - `src/main/java/com/muxai/gateway/api/dto/OpenAiChatRequest.java` — add 3 fields at end; forward in `toInternal()`.
  - `src/main/java/com/muxai/gateway/provider/anthropic/AnthropicProvider.java` — warn-and-drop the 3 fields inside `toAnthropic`.
- **Test**
  - `src/test/java/com/muxai/gateway/provider/model/ChatRequestTest.java` — new; covers field round-trip through `withX` helpers and 9-arg convenience constructor.
  - `src/test/java/com/muxai/gateway/api/dto/OpenAiChatRequestTest.java` — new; covers `toInternal()` propagation.
  - `src/test/java/com/muxai/gateway/provider/openai/OpenAiProviderTest.java` — extend existing; assert new fields appear verbatim in outbound body.
  - `src/test/java/com/muxai/gateway/provider/anthropic/AnthropicTranslationTest.java` — extend existing; assert warnings fire and fields are absent from wire body.

---

### Task 1: Add fields to internal `ChatRequest`

**Files:**
- Modify: `src/main/java/com/muxai/gateway/provider/model/ChatRequest.java`
- Test: `src/test/java/com/muxai/gateway/provider/model/ChatRequestTest.java` (new)

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/muxai/gateway/provider/model/ChatRequestTest.java`:

```java
package com.muxai.gateway.provider.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ChatRequestTest {

    @Test
    void newFieldsRoundTripThroughWithHelpers() {
        Object responseFormat = Map.of("type", "json_object");
        Object streamOptions = Map.of("include_usage", true);
        ChatRequest r = new ChatRequest(
                "gpt-4o",
                List.of(new ChatMessage("user", "hi")),
                0.0, null, 50, null, null, null, null,
                responseFormat, 42, streamOptions);

        assertThat(r.responseFormat()).isEqualTo(responseFormat);
        assertThat(r.seed()).isEqualTo(42);
        assertThat(r.streamOptions()).isEqualTo(streamOptions);

        ChatRequest renamed = r.withModel("gpt-4o-mini");
        assertThat(renamed.responseFormat()).isEqualTo(responseFormat);
        assertThat(renamed.seed()).isEqualTo(42);
        assertThat(renamed.streamOptions()).isEqualTo(streamOptions);

        ChatRequest streaming = r.withStream(Boolean.TRUE);
        assertThat(streaming.stream()).isTrue();
        assertThat(streaming.responseFormat()).isEqualTo(responseFormat);

        ChatRequest rewired = r.withMessages(List.of(new ChatMessage("user", "hello")));
        assertThat(rewired.messages().get(0).content()).isEqualTo("hello");
        assertThat(rewired.seed()).isEqualTo(42);
    }

    @Test
    void nineArgConvenienceConstructorNullsNewFields() {
        ChatRequest r = new ChatRequest(
                "gpt-4o",
                List.of(new ChatMessage("user", "hi")),
                0.0, null, 50, null, null, null, null);

        assertThat(r.responseFormat()).isNull();
        assertThat(r.seed()).isNull();
        assertThat(r.streamOptions()).isNull();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -Dtest=ChatRequestTest test`
Expected: compile failure — `responseFormat()`, `seed()`, `streamOptions()` don't exist.

- [ ] **Step 3: Add fields and update helpers**

Replace `src/main/java/com/muxai/gateway/provider/model/ChatRequest.java` in full:

```java
package com.muxai.gateway.provider.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatRequest(
        String model,
        List<ChatMessage> messages,
        Double temperature,
        @JsonProperty("top_p") Double topP,
        @JsonProperty("max_tokens") Integer maxTokens,
        List<String> stop,
        Boolean stream,
        List<Tool> tools,
        @JsonProperty("tool_choice") Object toolChoice,
        @JsonProperty("response_format") Object responseFormat,
        Integer seed,
        @JsonProperty("stream_options") Object streamOptions
) {
    public ChatRequest(String model, List<ChatMessage> messages, Double temperature,
                       Double topP, Integer maxTokens, List<String> stop, Boolean stream) {
        this(model, messages, temperature, topP, maxTokens, stop, stream,
                null, null, null, null, null);
    }

    public ChatRequest(String model, List<ChatMessage> messages, Double temperature,
                       Double topP, Integer maxTokens, List<String> stop, Boolean stream,
                       List<Tool> tools, Object toolChoice) {
        this(model, messages, temperature, topP, maxTokens, stop, stream,
                tools, toolChoice, null, null, null);
    }

    public ChatRequest withModel(String newModel) {
        return new ChatRequest(newModel, messages, temperature, topP, maxTokens, stop,
                stream, tools, toolChoice, responseFormat, seed, streamOptions);
    }

    public ChatRequest withMessages(List<ChatMessage> newMessages) {
        return new ChatRequest(model, newMessages, temperature, topP, maxTokens, stop,
                stream, tools, toolChoice, responseFormat, seed, streamOptions);
    }

    public ChatRequest withStream(Boolean newStream) {
        return new ChatRequest(model, messages, temperature, topP, maxTokens, stop,
                newStream, tools, toolChoice, responseFormat, seed, streamOptions);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -Dtest=ChatRequestTest test`
Expected: PASS both tests.

- [ ] **Step 5: Run the full unit suite to confirm no regressions**

Run: `mvn test`
Expected: all green. Existing call sites that pass 7 or 9 args still compile via the two convenience constructors.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/muxai/gateway/provider/model/ChatRequest.java \
        src/test/java/com/muxai/gateway/provider/model/ChatRequestTest.java
git commit -m "feat(model): add response_format, seed, stream_options to ChatRequest"
```

---

### Task 2: Add fields to `OpenAiChatRequest` DTO + propagate in `toInternal`

**Files:**
- Modify: `src/main/java/com/muxai/gateway/api/dto/OpenAiChatRequest.java`
- Test: `src/test/java/com/muxai/gateway/api/dto/OpenAiChatRequestTest.java` (new)

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/muxai/gateway/api/dto/OpenAiChatRequestTest.java`:

```java
package com.muxai.gateway.api.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.muxai.gateway.provider.model.ChatMessage;
import com.muxai.gateway.provider.model.ChatRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiChatRequestTest {

    @Test
    void toInternalPropagatesNewFields() {
        Object responseFormat = Map.of("type", "json_object");
        Object streamOptions = Map.of("include_usage", true);
        OpenAiChatRequest dto = new OpenAiChatRequest(
                "gpt-4o",
                List.of(new ChatMessage("user", "hi")),
                0.0, null, 50, null, null, null, null,
                responseFormat, 42, streamOptions);

        ChatRequest internal = dto.toInternal();

        assertThat(internal.responseFormat()).isEqualTo(responseFormat);
        assertThat(internal.seed()).isEqualTo(42);
        assertThat(internal.streamOptions()).isEqualTo(streamOptions);
    }

    @Test
    void deserializesSnakeCaseFromWire() throws Exception {
        String json = """
            {
              "model": "gpt-4o",
              "messages": [{"role":"user","content":"hi"}],
              "response_format": {"type":"json_object"},
              "seed": 42,
              "stream_options": {"include_usage": true}
            }
            """;
        OpenAiChatRequest dto = new ObjectMapper().readValue(json, OpenAiChatRequest.class);

        assertThat(dto.responseFormat()).isInstanceOf(Map.class);
        assertThat(dto.seed()).isEqualTo(42);
        assertThat(dto.streamOptions()).isInstanceOf(Map.class);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -Dtest=OpenAiChatRequestTest test`
Expected: compile failure — DTO doesn't have `responseFormat`, `seed`, `streamOptions`.

- [ ] **Step 3: Add fields to DTO**

Replace `src/main/java/com/muxai/gateway/api/dto/OpenAiChatRequest.java` in full:

```java
package com.muxai.gateway.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.muxai.gateway.provider.model.ChatMessage;
import com.muxai.gateway.provider.model.ChatRequest;
import com.muxai.gateway.provider.model.Tool;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record OpenAiChatRequest(
        @NotBlank String model,
        @NotEmpty @Valid List<@NotNull ChatMessage> messages,
        Double temperature,
        @JsonProperty("top_p") Double topP,
        @JsonProperty("max_tokens") Integer maxTokens,
        List<String> stop,
        Boolean stream,
        List<Tool> tools,
        @JsonProperty("tool_choice") Object toolChoice,
        @JsonProperty("response_format") Object responseFormat,
        Integer seed,
        @JsonProperty("stream_options") Object streamOptions
) {
    public ChatRequest toInternal() {
        return new ChatRequest(model, messages, temperature, topP, maxTokens, stop,
                stream, tools, toolChoice, responseFormat, seed, streamOptions);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -Dtest=OpenAiChatRequestTest test`
Expected: PASS both tests.

- [ ] **Step 5: Run full unit suite**

Run: `mvn test`
Expected: all green. Any existing DTO construction via `new OpenAiChatRequest(9 args)` is now a compile error if not updated — but nothing in the codebase does that today (the DTO is always built via Jackson from HTTP); integration tests build via JSON literals.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/muxai/gateway/api/dto/OpenAiChatRequest.java \
        src/test/java/com/muxai/gateway/api/dto/OpenAiChatRequestTest.java
git commit -m "feat(api): add response_format, seed, stream_options to OpenAiChatRequest"
```

---

### Task 3: Assert OpenAI passthrough of the three fields

**Files:**
- Test: `src/test/java/com/muxai/gateway/provider/openai/OpenAiProviderTest.java`

This task verifies no code change is required on the OpenAI side. Jackson serialises the new fields via the record's `@JsonProperty` annotations; the provider's `bodyValue(request.withStream(null))` sends them verbatim.

- [ ] **Step 1: Read the existing test setup**

Run: `head -60 src/test/java/com/muxai/gateway/provider/openai/OpenAiProviderTest.java`
Note the WireMock pattern: `@WireMockTest`, `stubFor(post(...).willReturn(okJson(...)))`, and `verify(postRequestedFor(urlEqualTo("/chat/completions")).withRequestBody(...))`.

- [ ] **Step 2: Add the new test**

Append the following test to the existing class (inside its `{ ... }` body, before the closing brace):

```java
@Test
void newFieldsArePassedThroughToProvider() {
    stubFor(post(urlEqualTo("/chat/completions"))
            .willReturn(okJson("""
                {"id":"x","object":"chat.completion","created":1,"model":"gpt-4o",
                 "choices":[{"index":0,"message":{"role":"assistant","content":"ok"},
                 "finish_reason":"stop"}]}
                """)));

    ChatRequest r = new ChatRequest(
            "gpt-4o",
            List.of(new ChatMessage("user", "hi")),
            0.0, null, 50, null, null, null, null,
            Map.of("type", "json_object"),
            42,
            Map.of("include_usage", true));

    provider.chat(r).block(Duration.ofSeconds(5));

    verify(postRequestedFor(urlEqualTo("/chat/completions"))
            .withRequestBody(matchingJsonPath("$.response_format.type", equalTo("json_object")))
            .withRequestBody(matchingJsonPath("$.seed", equalTo("42")))
            .withRequestBody(matchingJsonPath("$.stream_options.include_usage", equalTo("true"))));
}
```

Add imports if they're missing from the top of the file:

```java
import java.time.Duration;
import java.util.Map;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
```

- [ ] **Step 3: Run the test**

Run: `mvn -Dtest=OpenAiProviderTest#newFieldsArePassedThroughToProvider test`
Expected: PASS. No implementation change — Jackson produces the correct wire JSON.

- [ ] **Step 4: Run the full unit suite**

Run: `mvn test`
Expected: all green.

- [ ] **Step 5: Commit**

```bash
git add src/test/java/com/muxai/gateway/provider/openai/OpenAiProviderTest.java
git commit -m "test(openai): verify response_format, seed, stream_options passthrough"
```

---

### Task 4: Anthropic drop-with-warning

**Files:**
- Modify: `src/main/java/com/muxai/gateway/provider/anthropic/AnthropicProvider.java`
- Test: `src/test/java/com/muxai/gateway/provider/anthropic/AnthropicTranslationTest.java`

Anthropic's `/messages` endpoint doesn't accept OpenAI's structured outputs, `seed`, or `stream_options`. When any are set, log a single warning per field and leave them out of the translated body — no compatibility shim.

- [ ] **Step 1: Write the failing test**

Add these tests to the existing `AnthropicTranslationTest` class (inside its body). Find the class declaration and insert before the closing brace:

```java
@Test
void responseFormatOnAnthropicLogsWarningAndIsDropped() {
    ChatRequest r = new ChatRequest(
            "claude-sonnet-4-6",
            List.of(new ChatMessage("user", "hi")),
            null, null, 100, null, null, null, null,
            Map.of("type", "json_object"),
            null, null);

    AnthropicProvider.AnthropicMessagesRequest body = provider.toAnthropic(r, false);

    // Anthropic wire record has no responseFormat/seed/streamOptions fields — their absence is the test.
    String json = toJson(body);
    assertThat(json).doesNotContain("response_format");
}

@Test
void seedOnAnthropicIsDropped() {
    ChatRequest r = new ChatRequest(
            "claude-sonnet-4-6",
            List.of(new ChatMessage("user", "hi")),
            null, null, 100, null, null, null, null,
            null, 42, null);

    AnthropicProvider.AnthropicMessagesRequest body = provider.toAnthropic(r, false);
    assertThat(toJson(body)).doesNotContain("\"seed\"");
}

@Test
void streamOptionsOnAnthropicIsDropped() {
    ChatRequest r = new ChatRequest(
            "claude-sonnet-4-6",
            List.of(new ChatMessage("user", "hi")),
            null, null, 100, null, null, null, null,
            null, null, Map.of("include_usage", true));

    AnthropicProvider.AnthropicMessagesRequest body = provider.toAnthropic(r, false);
    assertThat(toJson(body)).doesNotContain("stream_options");
}

private static String toJson(Object o) {
    try {
        return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(o);
    } catch (Exception e) {
        throw new RuntimeException(e);
    }
}
```

Add the imports if absent at the top of the file:

```java
import java.util.Map;
```

- [ ] **Step 2: Run the tests to verify they fail or pass accidentally**

Run: `mvn -Dtest=AnthropicTranslationTest#responseFormatOnAnthropicLogsWarningAndIsDropped+AnthropicTranslationTest#seedOnAnthropicIsDropped+AnthropicTranslationTest#streamOptionsOnAnthropicIsDropped test`

Expected: likely PASS on all three — the current `AnthropicMessagesRequest` record has no place to put these fields, so they're silently dropped. The point of Task 4 is to add the observable side-effect (warning log) so operators know the drop happened. The implementation step adds warn logging and makes the test explicitly assert the warning.

- [ ] **Step 3: Tighten the test to assert warnings fire**

Replace the three tests just added with versions that also capture logs. Use a Logback ListAppender:

```java
@Test
void responseFormatOnAnthropicLogsWarningAndIsDropped() {
    var appender = attachAppender();
    ChatRequest r = new ChatRequest(
            "claude-sonnet-4-6",
            List.of(new ChatMessage("user", "hi")),
            null, null, 100, null, null, null, null,
            Map.of("type", "json_object"),
            null, null);

    AnthropicProvider.AnthropicMessagesRequest body = provider.toAnthropic(r, false);

    assertThat(toJson(body)).doesNotContain("response_format");
    assertThat(appender.list)
            .extracting(e -> e.getFormattedMessage())
            .anyMatch(m -> m.contains("response_format") && m.contains("claude-sonnet-4-6"));
}

@Test
void seedOnAnthropicIsDroppedAndWarned() {
    var appender = attachAppender();
    ChatRequest r = new ChatRequest(
            "claude-sonnet-4-6",
            List.of(new ChatMessage("user", "hi")),
            null, null, 100, null, null, null, null,
            null, 42, null);

    provider.toAnthropic(r, false);
    assertThat(appender.list)
            .extracting(e -> e.getFormattedMessage())
            .anyMatch(m -> m.contains("seed"));
}

@Test
void streamOptionsOnAnthropicIsDroppedAndWarned() {
    var appender = attachAppender();
    ChatRequest r = new ChatRequest(
            "claude-sonnet-4-6",
            List.of(new ChatMessage("user", "hi")),
            null, null, 100, null, null, null, null,
            null, null, Map.of("include_usage", true));

    provider.toAnthropic(r, false);
    assertThat(appender.list)
            .extracting(e -> e.getFormattedMessage())
            .anyMatch(m -> m.contains("stream_options"));
}

private static ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> attachAppender() {
    ch.qos.logback.classic.Logger logger =
            (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(AnthropicProvider.class);
    var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
    appender.start();
    logger.addAppender(appender);
    return appender;
}

private static String toJson(Object o) {
    try { return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(o); }
    catch (Exception e) { throw new RuntimeException(e); }
}
```

- [ ] **Step 4: Run tests to verify they now fail**

Run: `mvn -Dtest=AnthropicTranslationTest test`
Expected: the three new tests FAIL — no warning log is currently emitted.

- [ ] **Step 5: Add warn-and-drop logic in `toAnthropic`**

Open `src/main/java/com/muxai/gateway/provider/anthropic/AnthropicProvider.java`.

Find this block near the top of `toAnthropic`:

```java
    AnthropicMessagesRequest toAnthropic(ChatRequest req, boolean stream) {
        String system = null;
        List<AnthropicMessage> conversation = new ArrayList<>();
```

Insert **at the very start of the method body** (before the `String system = null;` line):

```java
        if (req.responseFormat() != null) {
            log.warn("Anthropic provider '{}' does not support response_format; dropping field (model={})",
                    props.id(), req.model());
        }
        if (req.seed() != null) {
            log.warn("Anthropic provider '{}' does not support seed; dropping field (model={})",
                    props.id(), req.model());
        }
        if (req.streamOptions() != null) {
            log.warn("Anthropic provider '{}' does not support stream_options; dropping field (model={})",
                    props.id(), req.model());
        }
```

No other changes to `toAnthropic` — the existing translator already omits these fields because `AnthropicMessagesRequest` doesn't have them.

- [ ] **Step 6: Run the tests to verify they pass**

Run: `mvn -Dtest=AnthropicTranslationTest test`
Expected: PASS, including the three new warn-assertions.

- [ ] **Step 7: Run the full verify suite**

Run: `mvn verify`
Expected: all unit and integration tests pass.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/muxai/gateway/provider/anthropic/AnthropicProvider.java \
        src/test/java/com/muxai/gateway/provider/anthropic/AnthropicTranslationTest.java
git commit -m "feat(anthropic): warn-and-drop response_format, seed, stream_options"
```

---

### Task 5: Document the new fields in CLAUDE.md

**Files:**
- Modify: `CLAUDE.md`

- [ ] **Step 1: Update the Roadmap section**

Open `CLAUDE.md`, find the Sprint 3 bullet:

```markdown
- **Sprint 3 — Provider feature parity** (~3-4 days)
  - `OpenAiChatRequest` + internal `ChatRequest` get `response_format`,
    `seed`, `stream_options`. OpenAI passes through verbatim; Anthropic drops
    with a logged warning (no shim).
```

Either remove that first bullet (if Plans 2 and 3 are also done) or mark it as shipped with a strikethrough. For now, leave the roadmap intact; Plan 3 (the full Sprint 3) will rewrite it.

- [ ] **Step 2: Update the "Internal model is OpenAI-shaped" section**

Open `CLAUDE.md`, find the list of translations Anthropic does. After the `tool_choice` bullet, add:

```markdown
- `response_format`, `seed`, `stream_options`: OpenAI passthrough; Anthropic
  drops each with a single `log.warn(...)` per field and continues (no shim).
```

- [ ] **Step 3: Commit**

```bash
git add CLAUDE.md
git commit -m "docs: note response_format/seed/stream_options translation"
```

---

## Self-Review Checklist

- **Spec coverage:** `response_format`, `seed`, `stream_options` all appear in Tasks 1 + 2 (model + DTO), Task 3 (OpenAI passthrough verification), Task 4 (Anthropic warn-and-drop). ✓
- **Placeholder scan:** no TBD / "implement later" / "similar to Task N" references. All code blocks contain actual code. ✓
- **Type consistency:** `responseFormat` (Object), `seed` (Integer), `streamOptions` (Object), and `@JsonProperty` snake-case mappings are identical across `ChatRequest`, `OpenAiChatRequest`, and tests. ✓
- **Back-compat:** `ChatRequest` keeps both the 7-arg and 9-arg convenience constructors so existing tests and `SemanticCache.KeyPayload` construction continue to compile. ✓
- **Observability:** Anthropic drops are logged via SLF4J with `{}` placeholders and include `props.id()` + model for grep-ability. ✓

---

## Execution Handoff

**Plan complete and saved to `docs/superpowers/plans/2026-04-24-sprint-3-request-fields.md`.**

Two execution options:
1. **Subagent-Driven (recommended)** — dispatch a fresh subagent per task, review between tasks.
2. **Inline Execution** — execute tasks in this session using `superpowers:executing-plans`, batched with checkpoints.

Which approach?
