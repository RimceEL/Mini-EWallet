package com.backend.backend.custom;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.LongStream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
public class RateLimiterRedisTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7.2"))
            .withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate template;
    private static RateLimiter rateLimiter;

    @BeforeAll
    static void setUp() {
        connectionFactory = new LettuceConnectionFactory(redis.getHost(), redis.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        template = new StringRedisTemplate(connectionFactory);
        rateLimiter = new RateLimiter(template);
    }

    @AfterAll
    static void tearDown() {
        connectionFactory.destroy();
    }

    private static String newKey() {
        return "test:" + UUID.randomUUID();
    }

    private static long ttl(String key) {
        Long t = template.getExpire(key, TimeUnit.SECONDS);
        return t == null ? -2 : t;
    }

    private static void shortenTtlTo100s(String key) {
        template.expire(key, Duration.ofSeconds(100));
    }

    private static <T> List<T> runConcurrently(int threads, Callable<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                return task.call();
            }));
        }
        ready.await();
        go.countDown();
        List<T> results = new ArrayList<>();
        for (Future<T> f : futures) {
            results.add(f.get(10, TimeUnit.SECONDS));
        }
        pool.shutdown();
        return results;
    }

    @Test
    void compareAndDelete_whenValueMatches_shouldDeleteKeyAndReturnTrue() {
        String key = newKey();
        template.opsForValue().set(key, "hash-a");

        boolean deleted = rateLimiter.compareAndDelete(key, "hash-a");

        assertThat(deleted).isTrue();
        assertThat(template.hasKey(key)).isFalse();
    }

    @Test
    void compareAndDelete_whenValueDiffers_shouldKeepKeyAndReturnFalse() {
        String key = newKey();
        template.opsForValue().set(key, "hash-b");

        boolean deleted = rateLimiter.compareAndDelete(key, "hash-a");

        assertThat(deleted).isFalse();
        assertThat(template.opsForValue().get(key)).isEqualTo("hash-b");
    }

    @Test
    void compareAndDelete_whenKeyMissing_shouldReturnFalse() {
        assertThat(rateLimiter.compareAndDelete(newKey(), "hash-a")).isFalse();
    }

    @RepeatedTest(20)
    void compareAndDelete_whenManyThreadsUseSameOtp_shouldLetExactlyOneWin() throws Exception {
        String key = newKey();
        template.opsForValue().set(key, "hash-a");

        List<Boolean> results = runConcurrently(20, () -> rateLimiter.compareAndDelete(key, "hash-a"));

        assertThat(results).filteredOn(r -> r).hasSize(1);
        assertThat(template.hasKey(key)).isFalse();
    }

    @Test
    void setExAndDelete_shouldSetValueWithTtlAndDeleteOtherKey() {
        String key = newKey();
        String failKey = newKey();
        template.opsForValue().set(failKey, "3");

        rateLimiter.setExAndDelete(key, "hash-a", Duration.ofMinutes(10), failKey);

        assertThat(template.opsForValue().get(key)).isEqualTo("hash-a");
        assertThat(ttl(key)).isBetween(1L, 600L);
        assertThat(template.hasKey(failKey)).isFalse();
    }

    @Test
    void setExAndDelete_whenSeveralKeysToDelete_shouldDeleteAllOfThem() {
        String key = newKey();
        String other1 = newKey();
        String other2 = newKey();
        template.opsForValue().set(other1, "x");
        template.opsForValue().set(other2, "y");

        rateLimiter.setExAndDelete(key, "v", Duration.ofMinutes(1), other1, other2);

        assertThat(template.hasKey(other1)).isFalse();
        assertThat(template.hasKey(other2)).isFalse();
        assertThat(template.opsForValue().get(key)).isEqualTo("v");
    }

    @Test
    void setExAndDelete_whenNoKeyToDelete_shouldStillSetValueWithTtl() {
        String key = newKey();

        rateLimiter.setExAndDelete(key, "v", Duration.ofMinutes(1));

        assertThat(template.opsForValue().get(key)).isEqualTo("v");
        assertThat(ttl(key)).isBetween(1L, 60L);
    }

    @Test
    void setExAndDelete_whenOtpResent_shouldInvalidateOldOtp() {
        String key = newKey();
        String failKey = newKey();

        rateLimiter.setExAndDelete(key, "hash-old", Duration.ofMinutes(10), failKey);
        rateLimiter.setExAndDelete(key, "hash-new", Duration.ofMinutes(10), failKey);

        assertThat(rateLimiter.compareAndDelete(key, "hash-old")).isFalse();
        assertThat(rateLimiter.compareAndDelete(key, "hash-new")).isTrue();
    }

    @Test
    void hit_firstHit_shouldReturnOneAndSetTtl() {
        String key = newKey();

        assertThat(rateLimiter.hit(key, 3600L, 5)).isEqualTo(1L);

        assertThat(ttl(key)).isBetween(1L, 3600L);
    }

    @Test
    void hit_shouldCountUpOnEachCall() {
        String key = newKey();

        assertThat(rateLimiter.hit(key, 3600L, 5)).isEqualTo(1L);
        assertThat(rateLimiter.hit(key, 3600L, 5)).isEqualTo(2L);
        assertThat(rateLimiter.hit(key, 3600L, 5)).isEqualTo(3L);
    }

    @Test
    void hit_beforeReachingRefreshAt_shouldNotTouchTtl() {
        String key = newKey();
        rateLimiter.hit(key, 3600L, 5);
        shortenTtlTo100s(key);

        rateLimiter.hit(key, 3600L, 5);

        assertThat(ttl(key)).isBetween(1L, 100L);
    }

    @Test
    void hit_whenCountReachesRefreshAt_shouldRestartTtl() {
        String key = newKey();
        for (int i = 0; i < 4; i++) {
            rateLimiter.hit(key, 3600L, 5);
        }
        shortenTtlTo100s(key);

        assertThat(rateLimiter.hit(key, 3600L, 5)).isEqualTo(5L);

        assertThat(ttl(key)).isBetween(101L, 3600L);
    }

    @Test
    void hit_afterRefreshAt_shouldNotExtendTtl() {
        String key = newKey();
        for (int i = 0; i < 5; i++) {
            rateLimiter.hit(key, 3600L, 5);
        }
        shortenTtlTo100s(key);

        assertThat(rateLimiter.hit(key, 3600L, 5)).isEqualTo(6L);

        assertThat(ttl(key)).isBetween(1L, 100L);
    }

    @Test
    void hit_whenKeyHasNoTtl_shouldGiveItOneSoItCannotLiveForever() {
        String key = newKey();
        template.opsForValue().set(key, "2");

        assertThat(rateLimiter.hit(key, 3600L, 5)).isEqualTo(3L);

        assertThat(ttl(key)).isBetween(1L, 3600L);
    }

    @RepeatedTest(10)
    void hit_whenManyThreadsHitSameKey_shouldNotLoseOrDuplicateAnyCount() throws Exception {
        String key = newKey();

        List<Long> counts = runConcurrently(50, () -> rateLimiter.hit(key, 3600L, 5));

        assertThat(counts).containsExactlyInAnyOrderElementsOf(
                LongStream.rangeClosed(1, 50).boxed().toList());
    }

    @Test
    void refund_shouldGiveBackOneAttempt() {
        String key = newKey();
        rateLimiter.hit(key, 3600L, 5);
        rateLimiter.hit(key, 3600L, 5);
        rateLimiter.hit(key, 3600L, 5);

        rateLimiter.refund(key);

        assertThat(rateLimiter.hit(key, 3600L, 5)).isEqualTo(3L);
    }

    @Test
    void refund_whenKeyMissing_shouldDoNothingAndNotCreateKey() {
        String key = newKey();

        rateLimiter.refund(key);

        assertThat(template.hasKey(key)).isFalse();
        assertThat(rateLimiter.hit(key, 3600L, 5)).isEqualTo(1L);
    }

    @Test
    void refund_shouldNotChangeTtl() {
        String key = newKey();
        rateLimiter.hit(key, 3600L, 5);
        shortenTtlTo100s(key);

        rateLimiter.refund(key);

        assertThat(ttl(key)).isBetween(1L, 100L);
    }

    @Test
    void refund_whenMoreRefundsThanHits_shouldNotMakeCounterNegative() {
        String key = newKey();
        rateLimiter.hit(key, 3600L, 5);

        rateLimiter.refund(key);
        rateLimiter.refund(key);
        rateLimiter.refund(key);

        assertThat(rateLimiter.hit(key, 3600L, 5)).isEqualTo(1L);
    }

    @Test
    void incrementAndExpire_shouldCountUpAndSetTtl() {
        String key = newKey();

        assertThat(rateLimiter.incrementAndExpire(key, Duration.ofMinutes(10))).isEqualTo(1L);
        assertThat(rateLimiter.incrementAndExpire(key, Duration.ofMinutes(10))).isEqualTo(2L);

        assertThat(ttl(key)).isBetween(1L, 600L);
    }

    @Test
    void incrementAndExpire_shouldRestartTtlOnEveryCall() {
        String key = newKey();
        rateLimiter.incrementAndExpire(key, Duration.ofMinutes(10));
        shortenTtlTo100s(key);

        rateLimiter.incrementAndExpire(key, Duration.ofMinutes(10));

        assertThat(ttl(key)).isBetween(101L, 600L);
    }

    @RepeatedTest(10)
    void incrementAndExpire_whenManyThreads_shouldNotLoseOrDuplicateAnyCount() throws Exception {
        String key = newKey();

        List<Long> counts = runConcurrently(50, () -> rateLimiter.incrementAndExpire(key, Duration.ofMinutes(10)));

        assertThat(counts).containsExactlyInAnyOrderElementsOf(
                LongStream.rangeClosed(1, 50).boxed().toList());
    }
}
