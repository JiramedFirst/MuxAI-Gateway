# Sprint 3 — Pluggable Cache Backend Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extract a `SemanticCache.Backend` interface so the Caffeine-backed exact-match store becomes one of several pluggable implementations (embedding-based backend to follow in a future plan).

**Architecture:** Behaviour-preserving refactor. The key derivation (`keyOf`) and eligibility check (`cacheable`) stay inside `SemanticCache`; only the storage primitives (`get` / `put` / `invalidateAll` / `size`) move to a new `SemanticCache.Backend` nested interface. The current Caffeine cache moves into a new `ExactMatchBackend` class registered as a `@Component`. Spring auto-wires the single implementation into `SemanticCache`'s constructor.

**Tech Stack:** Java 21, Caffeine (unchanged), JUnit 5 + AssertJ, Spring component scan.

---

## File Structure

- **Create**
  - `src/main/java/com/muxai/gateway/cache/ExactMatchBackend.java` — new Caffeine-backed `Backend` implementation.
  - `src/test/java/com/muxai/gateway/cache/ExactMatchBackendTest.java` — unit tests for the new class.
- **Modify**
  - `src/main/java/com/muxai/gateway/cache/SemanticCache.java` — add nested `Backend` interface; change constructor to inject `Backend`; delegate storage methods.
  - `src/test/java/com/muxai/gateway/cache/SemanticCacheTest.java` — update the `cache(...)` helper to inject an `ExactMatchBackend`.
  - `CLAUDE.md` — note that `SemanticCache.Backend` mirrors the existing `RateLimiter.Backend` pluggability pattern.

---

### Task 1: Define `Backend` interface, create `ExactMatchBackend`, refactor `SemanticCache`

This is an atomic refactor — the interface, implementation, and `SemanticCache` swap must land together to keep the codebase compiling. Steps are ordered to minimise intermediate broken states.

**Files:**
- Create: `src/main/java/com/muxai/gateway/cache/ExactMatchBackend.java`
- Modify: `src/main/java/com/muxai/gateway/cache/SemanticCache.java`
- Create: `src/test/java/com/muxai/gateway/cache/ExactMatchBackendTest.java`
- Modify: `src/test/java/com/muxai/gateway/cache/SemanticCacheTest.java`

- [ ] **Step 1: Write the failing test for the new class**

Create `src/test/java/com/muxai/gateway/cache/ExactMatchBackendTest.java`:

```java
package com.muxai.gateway.cache;

import com.muxai.gateway.provider.model.ChatMessage;
import com.muxai.gateway.provider.model.ChatResponse;
import com.muxai.gateway.provider.model.Usage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExactMatchBackendTest {

    private static ExactMatchBackend backend() {
        return new ExactMatchBackend(new CacheProperties(true, 10, 60L, 0.0));
    }

    private static ChatResponse resp(String text) {
        return new ChatResponse("id", "chat.completion", 1L, "gpt-4o",
                List.of(new ChatResponse.Choice(0, new ChatMessage("assistant", text), "stop")),
                Usage.of(1, 1));
    }

    @Test
    void putThenGetReturnsStoredResponse() {
        ExactMatchBackend b = backend();
        b.put("k1", resp("hello"));
        ChatResponse hit = b.get("k1");
        assertThat(hit).isNotNull();
        assertThat(hit.choices().get(0).message().content()).isEqualTo("hello");
    }

    @Test
    void getOnMissingKeyReturnsNull() {
        assertThat(backend().get("missing")).isNull();
    }

    @Test
    void invalidateAllClearsEntries() {
        ExactMatchBackend b = backend();
        b.put("k1", resp("A"));
        b.put("k2", resp("B"));
        b.invalidateAll();
        assertThat(b.get("k1")).isNull();
        assertThat(b.get("k2")).isNull();
    }

    @Test
    void sizeReflectsInsertions() {
        ExactMatchBackend b = backend();
        assertThat(b.size()).isZero();
        b.put("k1", resp("A"));
        b.put("k2", resp("B"));
        assertThat(b.size()).isEqualTo(2);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -Dtest=ExactMatchBackendTest test`
Expected: compile failure — `ExactMatchBackend` doesn't exist.

- [ ] **Step 3: Add the `Backend` nested interface to `SemanticCache`**

Open `src/main/java/com/muxai/gateway/cache/SemanticCache.java`. Add this nested interface inside the class, immediately after the constructor (so readers find it near the dependency it's used for):

```java
    /**
     * Storage primitive for {@link SemanticCache}. {@link ExactMatchBackend}
     * ships today; an embedding-similarity backend can be swapped in without
     * touching callers because key derivation and eligibility checks live in
     * {@link SemanticCache}, not here.
     */
    public interface Backend {
        /** Return the cached response for {@code key}, or {@code null} if absent. */
        ChatResponse get(String key);

        /** Store {@code response} under {@code key}. Caller guarantees non-null inputs. */
        void put(String key, ChatResponse response);

        /** Drop every entry. */
        void invalidateAll();

        /** Approximate entry count; best-effort for stats / tests. */
        long size();
    }
```

(Do not remove the Caffeine field or the existing methods yet — intermediate compile stages need them.)

- [ ] **Step 4: Create `ExactMatchBackend`**

Create `src/main/java/com/muxai/gateway/cache/ExactMatchBackend.java`:

```java
package com.muxai.gateway.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.muxai.gateway.provider.model.ChatResponse;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Caffeine-backed exact-match implementation of {@link SemanticCache.Backend}.
 *
 * Behaviour matches what was previously inlined in {@link SemanticCache}:
 * bounded size, write-expire TTL, and `recordStats()` for Micrometer
 * integration. No semantic change — the interface extraction is purely for
 * pluggability.
 */
@Component
public class ExactMatchBackend implements SemanticCache.Backend {

    private final Cache<String, ChatResponse> cache;

    public ExactMatchBackend(CacheProperties props) {
        this.cache = Caffeine.newBuilder()
                .maximumSize(props.maxEntriesOrDefault())
                .expireAfterWrite(Duration.ofSeconds(props.ttlSecondsOrDefault()))
                .recordStats()
                .build();
    }

    @Override
    public ChatResponse get(String key) {
        return cache.getIfPresent(key);
    }

    @Override
    public void put(String key, ChatResponse response) {
        cache.put(key, response);
    }

    @Override
    public void invalidateAll() {
        cache.invalidateAll();
    }

    @Override
    public long size() {
        return cache.estimatedSize();
    }
}
```

- [ ] **Step 5: Run the new-class test to verify it passes**

Run: `mvn -Dtest=ExactMatchBackendTest test`
Expected: PASS — all four tests.

- [ ] **Step 6: Refactor `SemanticCache` to depend on `Backend`**

Replace `src/main/java/com/muxai/gateway/cache/SemanticCache.java` in full:

```java
package com.muxai.gateway.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.muxai.gateway.provider.model.ChatRequest;
import com.muxai.gateway.provider.model.ChatResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Exact-match request cache for chat completions.
 *
 * The "semantic" name tracks the roadmap: today the {@link ExactMatchBackend}
 * hashes the full normalized request; a future embedding-similarity backend
 * can drop in behind the same {@link Backend} interface without changing
 * callers.
 *
 * Keying is intentionally strict — model, messages, and deterministic
 * sampling parameters are all folded into the hash. A single varied token
 * (different temperature, extra system instruction, new user turn) produces
 * a different key, which avoids cross-contamination between conversations.
 * Streaming, tool-enabled, and high-temperature requests bypass the cache
 * entirely — see {@link #cacheable(ChatRequest)}.
 */
@Component
public class SemanticCache {

    private static final Logger log = LoggerFactory.getLogger(SemanticCache.class);

    private final CacheProperties props;
    private final ObjectMapper mapper;
    private final Backend backend;

    public SemanticCache(CacheProperties props, ObjectMapper mapper, Backend backend) {
        this.props = props;
        this.mapper = mapper;
        this.backend = backend;
    }

    public boolean enabled() { return props.enabledOrDefault(); }

    public ChatResponse lookup(ChatRequest request) {
        if (!cacheable(request)) return null;
        String key = keyOf(request);
        if (key == null) return null;
        ChatResponse hit = backend.get(key);
        if (hit != null && log.isDebugEnabled()) {
            log.debug("semantic_cache hit key={}", key.substring(0, 12));
        }
        return hit;
    }

    public void store(ChatRequest request, ChatResponse response) {
        if (!cacheable(request) || response == null) return;
        String key = keyOf(request);
        if (key == null) return;
        backend.put(key, response);
    }

    public long size() { return backend.size(); }

    public void invalidateAll() { backend.invalidateAll(); }

    boolean cacheable(ChatRequest request) {
        if (!props.enabledOrDefault()) return false;
        if (request == null || request.messages() == null || request.messages().isEmpty()) return false;
        if (Boolean.TRUE.equals(request.stream())) return false;
        if (request.tools() != null && !request.tools().isEmpty()) return false;
        double temp = request.temperature() != null ? request.temperature() : 1.0;
        return temp <= props.maxCacheableTemperatureOrDefault();
    }

    private String keyOf(ChatRequest request) {
        try {
            byte[] payload = mapper.writeValueAsBytes(new KeyPayload(
                    request.model(),
                    request.messages(),
                    request.temperature(),
                    request.topP(),
                    request.maxTokens(),
                    request.stop()));
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(payload));
        } catch (Exception e) {
            log.warn("semantic_cache key hash failed: {}", e.getMessage());
            return null;
        }
    }

    /** Mirror of the request's cache-sensitive fields. Kept record-shaped so Jackson produces stable key bytes. */
    private record KeyPayload(
            String model,
            Object messages,
            Double temperature,
            Double topP,
            Integer maxTokens,
            Object stop
    ) {}

    /**
     * Storage primitive for {@link SemanticCache}. {@link ExactMatchBackend}
     * ships today; an embedding-similarity backend can be swapped in without
     * touching callers because key derivation and eligibility checks live in
     * {@link SemanticCache}, not here.
     */
    public interface Backend {
        /** Return the cached response for {@code key}, or {@code null} if absent. */
        ChatResponse get(String key);

        /** Store {@code response} under {@code key}. Caller guarantees non-null inputs. */
        void put(String key, ChatResponse response);

        /** Drop every entry. */
        void invalidateAll();

        /** Approximate entry count; best-effort for stats / tests. */
        long size();
    }

    // Package-private for tests.
    static MessageDigest newSha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    static String hex(byte[] b) {
        return HexFormat.of().formatHex(b);
    }

    @SuppressWarnings("unused")
    private static byte[] utf8(String s) { return s.getBytes(StandardCharsets.UTF_8); }
}
```

The class now depends on `Backend` (constructor-injected), Caffeine imports are gone, and the inline cache field has moved to `ExactMatchBackend`.

- [ ] **Step 7: Update `SemanticCacheTest` helper**

Open `src/test/java/com/muxai/gateway/cache/SemanticCacheTest.java`. Find:

```java
    private static SemanticCache cache(boolean enabled) {
        CacheProperties props = new CacheProperties(enabled, 10, 60L, 0.0);
        return new SemanticCache(props, new ObjectMapper());
    }
```

Replace with:

```java
    private static SemanticCache cache(boolean enabled) {
        CacheProperties props = new CacheProperties(enabled, 10, 60L, 0.0);
        return new SemanticCache(props, new ObjectMapper(), new ExactMatchBackend(props));
    }
```

- [ ] **Step 8: Run the whole cache test suite**

Run: `mvn -Dtest='SemanticCacheTest,ExactMatchBackendTest' test`
Expected: PASS on every test. All existing `SemanticCacheTest` assertions hold because behaviour is unchanged.

- [ ] **Step 9: Run the full verify suite**

Run: `mvn verify`
Expected: all unit and integration tests pass. `SemanticCache` is autowired in `ChatController`; Spring will now supply `ExactMatchBackend` automatically via its `@Component`.

- [ ] **Step 10: Commit**

```bash
git add src/main/java/com/muxai/gateway/cache/SemanticCache.java \
        src/main/java/com/muxai/gateway/cache/ExactMatchBackend.java \
        src/test/java/com/muxai/gateway/cache/SemanticCacheTest.java \
        src/test/java/com/muxai/gateway/cache/ExactMatchBackendTest.java
git commit -m "refactor(cache): extract SemanticCache.Backend interface, add ExactMatchBackend"
```

---

### Task 2: Document the backend split in CLAUDE.md

**Files:**
- Modify: `CLAUDE.md`

- [ ] **Step 1: Update the "Feature toggles" section**

Open `CLAUDE.md`. Find the paragraph that starts with `SemanticCache is exact-match SHA-256 keyed on`. Replace it with:

```markdown
`SemanticCache` is exact-match SHA-256 keyed on
`(model, messages, temperature, top_p, max_tokens, stop)`. Streaming, tool
calls, and `temperature > max-cacheable-temperature` (default 0.0) bypass the
cache entirely — see `cacheable()`. Storage is delegated to a
`SemanticCache.Backend` (currently only `ExactMatchBackend` — Caffeine-backed,
bounded, write-expire TTL). The interface mirrors the `RateLimiter.Backend`
pattern so an embedding-similarity backend can drop in without touching
callers.
```

- [ ] **Step 2: Commit**

```bash
git add CLAUDE.md
git commit -m "docs: document SemanticCache.Backend pluggability"
```

---

## Self-Review Checklist

- **Spec coverage:** `SemanticCache.Backend` interface (Task 1 Step 3); `ExactMatchBackend` (Task 1 Step 4); `SemanticCache` refactor to depend on `Backend` (Task 1 Step 6). ✓
- **Placeholder scan:** all code shown in full; no `TODO` / "rest unchanged" shortcuts. The full replacement of `SemanticCache.java` is spelled out. ✓
- **Type consistency:** `Backend.get` / `put` / `invalidateAll` / `size` signatures are identical in `SemanticCache` (nested interface), `ExactMatchBackend` (implementation), and both tests. ✓
- **Behaviour preservation:** the `SemanticCacheTest` suite is untouched except for the helper's constructor — every existing assertion still exercises the same end-to-end path. ✓
- **Spring wiring:** `ExactMatchBackend` is `@Component`, `SemanticCache` remains `@Component`. Only one `Backend` bean exists, so auto-wiring is unambiguous — no `@Primary` needed. ✓

---

## Execution Handoff

**Plan complete and saved to `docs/superpowers/plans/2026-04-24-sprint-3-cache-backend-interface.md`.**

Two execution options:
1. **Subagent-Driven (recommended)** — dispatch a fresh subagent per task, review between tasks.
2. **Inline Execution** — execute tasks in this session using `superpowers:executing-plans`, batched with checkpoints.

Which approach?
