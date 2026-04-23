package com.muxai.gateway.ratelimit;

import com.muxai.gateway.config.GatewayProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimiterTest {

    private static GatewayProperties props(GatewayProperties.ApiKey... keys) {
        return new GatewayProperties(List.of(), List.of(), List.of(keys));
    }

    private static GatewayProperties.ApiKey key(String k, String appId, Integer rateLimitPerMin) {
        return new GatewayProperties.ApiKey(k, appId, rateLimitPerMin,
                null, null, null, null, null);
    }

    @Test
    void unconfiguredAppIsUnlimited() {
        RateLimiter rl = RateLimiter.inMemory(props());
        for (int i = 0; i < 1000; i++) {
            assertThat(rl.tryAcquire("anyone").allowed()).isTrue();
        }
    }

    @Test
    void nullLimitIsUnlimited() {
        RateLimiter rl = RateLimiter.inMemory(props(
                key("k", "app", null)));
        for (int i = 0; i < 500; i++) {
            assertThat(rl.tryAcquire("app").allowed()).isTrue();
        }
    }

    @Test
    void zeroOrNegativeLimitIsUnlimited() {
        RateLimiter rl = RateLimiter.inMemory(props(
                key("k1", "zero", 0),
                key("k2", "neg", -5)));
        assertThat(rl.tryAcquire("zero").allowed()).isTrue();
        assertThat(rl.tryAcquire("neg").allowed()).isTrue();
        assertThat(rl.limitFor("zero")).isNull();
        assertThat(rl.limitFor("neg")).isNull();
    }

    @Test
    void burstUpToLimitThenRejects() {
        int limit = 5;
        RateLimiter rl = RateLimiter.inMemory(props(
                key("k", "app", limit)));

        for (int i = 0; i < limit; i++) {
            RateLimiter.Decision d = rl.tryAcquire("app");
            assertThat(d.allowed()).as("request #%d should be allowed", i + 1).isTrue();
            assertThat(d.limit()).isEqualTo(limit);
            assertThat(d.remaining()).isEqualTo(limit - 1 - i);
        }

        RateLimiter.Decision rejected = rl.tryAcquire("app");
        assertThat(rejected.allowed()).isFalse();
        assertThat(rejected.remaining()).isEqualTo(0);
        assertThat(rejected.limit()).isEqualTo(limit);
        assertThat(rejected.retryAfterMillis()).isGreaterThanOrEqualTo(1L);
        // With limit=5/min, one token refills in ~12s (12000ms).
        assertThat(rejected.retryAfterMillis()).isLessThanOrEqualTo(12_001L);
    }

    @Test
    void bucketsAreIsolatedAcrossApps() {
        RateLimiter rl = RateLimiter.inMemory(props(
                key("k1", "alpha", 2),
                key("k2", "beta", 2)));

        assertThat(rl.tryAcquire("alpha").allowed()).isTrue();
        assertThat(rl.tryAcquire("alpha").allowed()).isTrue();
        assertThat(rl.tryAcquire("alpha").allowed()).isFalse();

        // beta's bucket is independent and still full.
        assertThat(rl.tryAcquire("beta").allowed()).isTrue();
        assertThat(rl.tryAcquire("beta").allowed()).isTrue();
        assertThat(rl.tryAcquire("beta").allowed()).isFalse();
    }

    @Test
    void duplicateAppIdTakesMaxLimit() {
        RateLimiter rl = RateLimiter.inMemory(props(
                key("k1", "app", 3),
                key("k2", "app", 10)));

        assertThat(rl.limitFor("app")).isEqualTo(10);
        for (int i = 0; i < 10; i++) {
            assertThat(rl.tryAcquire("app").allowed()).isTrue();
        }
        assertThat(rl.tryAcquire("app").allowed()).isFalse();
    }

    @Test
    void nullAppIdIsUnlimited() {
        RateLimiter rl = RateLimiter.inMemory(props(
                key("k", "app", 1)));
        assertThat(rl.tryAcquire(null).allowed()).isTrue();
    }

    @Test
    void refillAllowsAnotherRequestAfterWait() throws InterruptedException {
        // 600/min = 10 per second = 1 token every ~100ms. Use a high rate so the test
        // finishes quickly without being flaky.
        RateLimiter rl = RateLimiter.inMemory(props(
                key("k", "app", 600)));

        for (int i = 0; i < 600; i++) {
            assertThat(rl.tryAcquire("app").allowed()).isTrue();
        }
        assertThat(rl.tryAcquire("app").allowed()).isFalse();

        Thread.sleep(200);
        assertThat(rl.tryAcquire("app").allowed()).isTrue();
    }
}
