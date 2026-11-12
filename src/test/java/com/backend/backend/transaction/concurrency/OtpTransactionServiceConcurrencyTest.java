package com.backend.backend.transaction.concurrency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.backend.backend.auth.customexception.email.TooManyResendAttemptsException;
import com.backend.backend.auth.customexception.otp.InvalidOtpException;
import com.backend.backend.auth.customexception.otp.InvalidResetTokenException;
import com.backend.backend.auth.customexception.otp.ManyOtpException;
import com.backend.backend.auth.service.AsyncMailRunner;
import com.backend.backend.auth.service.EmailService;
import com.backend.backend.auth.service.OtpService;
import com.backend.backend.custom.RateLimiter;
import com.backend.backend.transaction.OtpTransactionService;
import com.backend.backend.transaction.Transaction;
import com.backend.backend.transaction.TransactionService;
import com.backend.backend.transaction.TransactionStatus;
import com.backend.backend.transaction.TransactionType;

@Testcontainers
@Timeout(value = 120, unit = TimeUnit.SECONDS)
public class OtpTransactionServiceConcurrencyTest {

    @Container
    static GenericContainer<?> redisContainer = new GenericContainer<>(DockerImageName.parse("redis:7.2"))
            .withExposedPorts(6379);

    private static final String FAIL_PREFIX = "transaction:otp-fail:";
    private static final String RESEND_PREFIX = "transaction:resend:";
    private static final String REFUND_PREFIX = "transaction:resend-refund:";

    private static final String RAW_OTP = "12345678";

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redis;
    private static RateLimiter rateLimiter;
    private static OtpService otpService;

    private EmailService emailService;
    private TransactionService transactionService;
    private OtpTransactionService service;

    private final List<String> sentOtps = new CopyOnWriteArrayList<>();
    private final Map<String, String> otpByRef = new ConcurrentHashMap<>();
    private String email;

    @BeforeAll
    static void setUpAll() {
        connectionFactory = new LettuceConnectionFactory(redisContainer.getHost(), redisContainer.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        redis = new StringRedisTemplate(connectionFactory);
        rateLimiter = new RateLimiter(redis);
        otpService = new OtpService("test-secret-test-secret-test-secret-123");
    }

    @AfterAll
    static void tearDownAll() {
        connectionFactory.destroy();
    }

    @BeforeEach
    void setUp() {
        emailService = mock(EmailService.class);
        transactionService = mock(TransactionService.class);
        when(transactionService.isUserWallet(any(), any())).thenReturn(true);

        sentOtps.clear();
        otpByRef.clear();
        doAnswer(inv -> {
            String otp = inv.getArgument(1);
            Transaction tx = inv.getArgument(3);
            sentOtps.add(otp);
            otpByRef.put(tx.getReferenceId(), otp);
            return null;
        }).when(emailService).sendTransactionOtp(any(), any(), anyInt(), any());

        service = new OtpTransactionService(redis, otpService, emailService, rateLimiter, transactionService,
                new AsyncMailRunner());
        ReflectionTestUtils.setField(service, "otpTtlMinutes", 10);
        ReflectionTestUtils.setField(service, "maxOtpAttempts", 5);
        ReflectionTestUtils.setField(service, "tokenTtlMinutes", 10);
        ReflectionTestUtils.setField(service, "maxResendAttempts", 5);
        ReflectionTestUtils.setField(service, "resendLockHours", 24L);

        email = "user-" + UUID.randomUUID() + "@test.com";
    }

    private record Outcome<T>(T value, Throwable error) {
        boolean ok() {
            return error == null;
        }
    }

    private static <T> List<Outcome<T>> runConcurrently(List<Callable<T>> tasks) throws Exception {
        int n = tasks.size();
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Outcome<T>>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    try {
                        return new Outcome<T>(task.call(), null);
                    } catch (Throwable t) {
                        return new Outcome<T>(null, t);
                    }
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            List<Outcome<T>> results = new ArrayList<>();
            for (Future<Outcome<T>> f : futures) {
                results.add(f.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private static <T> List<Outcome<T>> runConcurrently(int threads, Callable<T> task) throws Exception {
        return runConcurrently(Collections.nCopies(threads, task));
    }

    private static long successes(List<? extends Outcome<?>> results) {
        return results.stream().filter(o -> o.ok()).count();
    }

    private static long count(List<? extends Outcome<?>> results, Class<? extends Throwable> type) {
        return results.stream().filter(o -> type.isInstance(o.error())).count();
    }

    private static Transaction newTx() {
        return Transaction.builder()
                .referenceId(UUID.randomUUID().toString())
                .type(TransactionType.TRANSFER)
                .status(TransactionStatus.PENDING)
                .sourceWalletId("wallet-src")
                .destinationWalletId("wallet-dst")
                .amount(BigDecimal.valueOf(1000))
                .build();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private String otpKey(Transaction tx) {
        return service.transactionOtpKey(tx.getReferenceId(), email);
    }

    private String tokenKey(Transaction tx) {
        return service.transactionTokenKey(tx.getReferenceId(), email);
    }

    private String failKey(Transaction tx) {
        return FAIL_PREFIX + tx.getReferenceId() + ":" + email;
    }

    private String resendKey(Transaction tx) {
        return RESEND_PREFIX + tx.getReferenceId() + ":" + email;
    }

    private String refundKey(Transaction tx) {
        return REFUND_PREFIX + tx.getReferenceId() + ":" + email;
    }

    private void seedOtp(Transaction tx, String rawOtp) {
        redis.opsForValue().set(otpKey(tx), otpService.hash(rawOtp), Duration.ofMinutes(10));
    }

    private void seedToken(Transaction tx, String rawToken) {
        redis.opsForValue().set(tokenKey(tx), sha256(rawToken), Duration.ofMinutes(10));
    }

    private void seedFailCount(Transaction tx, int n) {
        redis.opsForValue().set(failKey(tx), String.valueOf(n), Duration.ofMinutes(20));
    }

    private String get(String key) {
        return redis.opsForValue().get(key);
    }

    @Test
    @DisplayName("Gửi OTP: 20 request đồng thời chỉ có đúng maxResendAttempts request được gửi mail")
    void sendOtp_concurrent_neverExceedsResendLimit() throws Exception {
        Transaction tx = newTx();

        var results = runConcurrently(20, () -> {
            service.transactionRequest(tx, email);
            return null;
        });

        assertThat(successes(results)).isEqualTo(5);
        assertThat(count(results, TooManyResendAttemptsException.class)).isEqualTo(15);
        verify(emailService, times(5)).sendTransactionOtp(any(), any(), anyInt(), any());
        assertThat(get(resendKey(tx))).isEqualTo("20");
        assertThat(redis.getExpire(resendKey(tx))).isBetween(1L, 24 * 3600L);
    }

    @Test
    @DisplayName("Gửi OTP: gửi đồng thời trong giới hạn => Redis chỉ giữ 1 OTP hợp lệ và reset bộ đếm sai")
    void sendOtp_concurrent_leavesOneValidOtp_andResetsFailCounter() throws Exception {
        Transaction tx = newTx();
        seedFailCount(tx, 3);

        var results = runConcurrently(5, () -> {
            service.transactionRequest(tx, email);
            return null;
        });

        assertThat(successes(results)).isEqualTo(5);
        assertThat(sentOtps).hasSize(5);

        String stored = get(otpKey(tx));
        assertThat(stored).isNotNull();
        assertThat(sentOtps).anyMatch(otp -> otpService.hash(otp).equals(stored));
        assertThat(redis.getExpire(otpKey(tx))).isBetween(1L, 600L);
        assertThat(get(failKey(tx))).isNull();
    }

    @Test
    @DisplayName("Gửi OTP: mail lỗi đồng thời => hoàn lượt tối đa 3 lần")
    void sendOtp_mailFailure_refundIsCappedAtThree() throws Exception {
        ReflectionTestUtils.setField(service, "maxResendAttempts", 100);
        doThrow(new RuntimeException("smtp down"))
                .when(emailService).sendTransactionOtp(any(), any(), anyInt(), any());
        Transaction tx = newTx();

        var results = runConcurrently(5, () -> {
            service.transactionRequest(tx, email);
            return null;
        });

        assertThat(successes(results)).isEqualTo(5);
        assertThat(get(refundKey(tx))).isEqualTo("5");
        assertThat(get(resendKey(tx))).isEqualTo("2");
    }

    @Test
    @DisplayName("Gửi OTP: mail lỗi đồng thời trong giới hạn hoàn => bộ đếm resend về 0, không âm")
    void sendOtp_mailFailure_counterReturnsToZero_neverNegative() throws Exception {
        doThrow(new RuntimeException("smtp down"))
                .when(emailService).sendTransactionOtp(any(), any(), anyInt(), any());
        Transaction tx = newTx();

        var results = runConcurrently(3, () -> {
            service.transactionRequest(tx, email);
            return null;
        });

        assertThat(successes(results)).isEqualTo(3);
        assertThat(get(resendKey(tx))).isEqualTo("0");
    }

    @Test
    @DisplayName("Verify OTP: 5 thread cùng nhập đúng OTP => đúng 1 token, 4 thread còn lại InvalidOtp")
    void verifyOtp_correctOtp_concurrent_exactlyOneToken() throws Exception {
        Transaction tx = newTx();
        seedOtp(tx, RAW_OTP);

        var results = runConcurrently(5, () -> service.verifyTransactionOtp(tx, email, RAW_OTP));

        assertThat(successes(results)).isEqualTo(1);
        assertThat(count(results, InvalidOtpException.class)).isEqualTo(4);

        String token = results.stream().filter(o -> o.ok()).map(Outcome::value).findFirst().orElseThrow();
        assertThat(token).hasSize(43);
        assertThat(get(tokenKey(tx))).isEqualTo(sha256(token));
        assertThat(get(otpKey(tx))).isNull();
    }

    @RepeatedTest(10)
    @DisplayName("Verify OTP: 20 thread nhập đúng OTP (vượt ngưỡng sai) => không bao giờ có quá 1 token")
    void verifyOtp_correctOtp_manyThreads_neverMoreThanOneToken() throws Exception {
        Transaction tx = newTx();
        seedOtp(tx, RAW_OTP);

        var results = runConcurrently(20, () -> service.verifyTransactionOtp(tx, email, RAW_OTP));

        long ok = successes(results);
        assertThat(ok).isLessThanOrEqualTo(1);
        assertThat(ok + count(results, InvalidOtpException.class) + count(results, ManyOtpException.class))
                .isEqualTo(20);
        if (ok == 1) {
            assertThat(get(tokenKey(tx))).isNotNull();
        }
    }

    @Test
    @DisplayName("Verify OTP: 20 thread nhập sai => đúng 5 InvalidOtp, 15 ManyOtp, OTP bị xoá, sau đó OTP đúng cũng bị chặn")
    void verifyOtp_wrongOtp_concurrent_blocksAfterMaxAttempts() throws Exception {
        Transaction tx = newTx();
        seedOtp(tx, RAW_OTP);

        var results = runConcurrently(20, () -> service.verifyTransactionOtp(tx, email, "00000000"));

        assertThat(successes(results)).isZero();
        assertThat(count(results, InvalidOtpException.class)).isEqualTo(5);
        assertThat(count(results, ManyOtpException.class)).isEqualTo(15);
        assertThat(get(failKey(tx))).isEqualTo("20");
        assertThat(get(otpKey(tx))).isNull();
        assertThat(get(tokenKey(tx))).isNull();
        assertThat(redis.getExpire(failKey(tx))).isBetween(1L, 1200L);

        assertThatThrownBy(() -> service.verifyTransactionOtp(tx, email, RAW_OTP))
                .isInstanceOf(ManyOtpException.class);
    }

    @RepeatedTest(20)
    @DisplayName("Verify OTP: race sát ngưỡng (đã sai 4 lần) giữa 1 request đúng và 4 request sai")
    void verifyOtp_borderlineRace_correctVsWrong() throws Exception {
        Transaction tx = newTx();
        seedOtp(tx, RAW_OTP);
        seedFailCount(tx, 4);

        List<Callable<String>> tasks = new ArrayList<>();
        tasks.add(() -> service.verifyTransactionOtp(tx, email, RAW_OTP));
        for (int i = 0; i < 4; i++) {
            tasks.add(() -> service.verifyTransactionOtp(tx, email, "00000000"));
        }

        var results = runConcurrently(tasks);

        assertThat(results.subList(1, 5)).noneMatch(o -> o.ok());
        assertThat(successes(results)).isLessThanOrEqualTo(1);
        assertThat(results).allSatisfy(o -> {
            if (!o.ok()) {
                assertThat(o.error()).isInstanceOfAny(InvalidOtpException.class, ManyOtpException.class);
            }
        });
        assertThat(get(otpKey(tx))).isNull();
    }

    @RepeatedTest(30)
    @DisplayName("Verify OTP vs gửi lại OTP: verify không bao giờ xoá nhầm OTP mới")
    void verifyOtp_vs_sendOtp_race_doesNotDeleteNewOtp() throws Exception {
        Transaction tx = newTx();
        seedOtp(tx, RAW_OTP);

        List<Callable<Object>> tasks = List.of(
                () -> service.verifyTransactionOtp(tx, email, RAW_OTP),
                () -> {
                    service.transactionRequest(tx, email);
                    return null;
                });
        var results = runConcurrently(tasks);

        assertThat(results.get(1).ok()).isTrue();
        if (!results.get(0).ok()) {
            assertThat(results.get(0).error()).isInstanceOf(InvalidOtpException.class);
        }

        assertThat(sentOtps).hasSize(1);
        String newOtp = sentOtps.get(0);
        assertThat(get(otpKey(tx))).isEqualTo(otpService.hash(newOtp));

        assertThat(service.verifyTransactionOtp(tx, email, newOtp)).isNotBlank();
    }

    @Test
    @DisplayName("Thiết kế: nhập đúng ở lượt cuối còn được phép (lượt thứ 5) vẫn thành công")
    void verifyOtp_correctOtpOnLastAllowedAttempt_succeeds() {
        Transaction tx = newTx();
        seedOtp(tx, RAW_OTP);
        seedFailCount(tx, 4);

        assertThat(service.verifyTransactionOtp(tx, email, RAW_OTP)).isNotBlank();
    }

    @Test
    @DisplayName("Thiết kế: nhập đúng sau khi đã hết lượt (lượt thứ 6) bị từ chối và OTP bị xoá")
    void verifyOtp_correctOtpAfterLimit_isRejected_andOtpDeleted() {
        Transaction tx = newTx();
        seedOtp(tx, RAW_OTP);
        seedFailCount(tx, 5);

        assertThatThrownBy(() -> service.verifyTransactionOtp(tx, email, RAW_OTP))
                .isInstanceOf(ManyOtpException.class);
        assertThat(get(otpKey(tx))).isNull();
        assertThat(get(tokenKey(tx))).isNull();
    }

    @Test
    @DisplayName("Verify OTP thành công: lưu hash token có TTL và reset bộ đếm sai")
    void verifyOtp_success_storesTokenHash_andResetsFailCounter() {
        Transaction tx = newTx();
        seedOtp(tx, RAW_OTP);
        seedFailCount(tx, 3);

        String token = service.verifyTransactionOtp(tx, email, RAW_OTP);

        assertThat(get(tokenKey(tx))).isEqualTo(sha256(token));
        assertThat(redis.getExpire(tokenKey(tx))).isBetween(1L, 600L);
        assertThat(get(failKey(tx))).isNull();
        assertThat(get(otpKey(tx))).isNull();
    }

    @Test
    @DisplayName("Verify OTP: 10 giao dịch khác nhau chạy đồng thời, mỗi giao dịch một token riêng")
    void verifyOtp_manyTransactions_noKeyBleed() throws Exception {
        List<Transaction> txs = IntStream.range(0, 10).mapToObj(i -> newTx()).toList();
        for (int i = 0; i < txs.size(); i++) {
            seedOtp(txs.get(i), String.format("%08d", i + 1));
        }

        List<Callable<String>> tasks = IntStream.range(0, txs.size())
                .mapToObj(i -> (Callable<String>) () -> service.verifyTransactionOtp(
                        txs.get(i), email, String.format("%08d", i + 1)))
                .toList();
        var results = runConcurrently(tasks);

        assertThat(successes(results)).isEqualTo(10);
        Set<String> tokens = results.stream().map(Outcome::value).collect(Collectors.toSet());
        assertThat(tokens).hasSize(10);
        for (int i = 0; i < txs.size(); i++) {
            assertThat(get(tokenKey(txs.get(i)))).isEqualTo(sha256(results.get(i).value()));
            assertThat(get(otpKey(txs.get(i)))).isNull();
        }
    }

    @Test
    @DisplayName("Verify OTP: request từ email khác (đồng thời) không ảnh hưởng OTP của chủ giao dịch")
    void verifyOtp_otherEmails_cannotTouchVictimOtp() throws Exception {
        Transaction tx = newTx();
        seedOtp(tx, RAW_OTP);

        List<Callable<String>> tasks = IntStream.range(0, 10)
                .mapToObj(i -> (Callable<String>) () -> service.verifyTransactionOtp(
                        tx, "attacker" + i + "@test.com", RAW_OTP))
                .toList();
        var results = runConcurrently(tasks);

        assertThat(successes(results)).isZero();
        assertThat(count(results, InvalidOtpException.class)).isEqualTo(10);
        assertThat(get(otpKey(tx))).isEqualTo(otpService.hash(RAW_OTP));
        assertThat(get(failKey(tx))).isNull();
        assertThat(service.verifyTransactionOtp(tx, email, RAW_OTP)).isNotBlank();
    }

    @Test
    @DisplayName("Execute: 20 thread cùng dùng một token hợp lệ => execute đúng 1 lần, key được dọn sạch")
    void execute_concurrent_sameToken_executesExactlyOnce() throws Exception {
        Transaction tx = newTx();
        seedToken(tx, "real-token");
        for (String k : List.of(resendKey(tx), otpKey(tx), failKey(tx), refundKey(tx))) {
            redis.opsForValue().set(k, "1", Duration.ofMinutes(10));
        }

        var results = runConcurrently(20, () -> {
            service.verifyTransaction(tx, email, "real-token");
            return null;
        });

        assertThat(successes(results)).isEqualTo(1);
        assertThat(count(results, InvalidResetTokenException.class)).isEqualTo(19);
        verify(transactionService, times(1)).execute(tx);
        assertThat(get(tokenKey(tx))).isNull();
        assertThat(get(resendKey(tx))).isNull();
        assertThat(get(otpKey(tx))).isNull();
        assertThat(get(failKey(tx))).isNull();
        assertThat(get(refundKey(tx))).isNull();
    }

    @Test
    @DisplayName("Execute: token sai đồng thời không làm mất token thật và không gọi execute")
    void execute_concurrent_wrongTokens_leaveRealTokenIntact() throws Exception {
        Transaction tx = newTx();
        seedToken(tx, "real-token");

        List<Callable<Object>> tasks = IntStream.range(0, 20)
                .mapToObj(i -> (Callable<Object>) () -> {
                    service.verifyTransaction(tx, email, "wrong-token-" + i);
                    return null;
                })
                .toList();
        var results = runConcurrently(tasks);

        assertThat(successes(results)).isZero();
        assertThat(count(results, InvalidResetTokenException.class)).isEqualTo(20);
        verify(transactionService, never()).execute(any());
        assertThat(get(tokenKey(tx))).isEqualTo(sha256("real-token"));

        service.verifyTransaction(tx, email, "real-token");
        verify(transactionService, times(1)).execute(tx);
    }

    @Test
    @DisplayName("Thiết kế: execute lỗi => token đã bị tiêu thụ, chỉ đúng 1 lần thử execute, key resend chưa bị dọn")
    void execute_failure_burnsToken_andOnlyOneAttempt() throws Exception {
        doThrow(new IllegalStateException("db down")).when(transactionService).execute(any());
        Transaction tx = newTx();
        seedToken(tx, "real-token");
        redis.opsForValue().set(resendKey(tx), "1", Duration.ofMinutes(10));

        var results = runConcurrently(20, () -> {
            service.verifyTransaction(tx, email, "real-token");
            return null;
        });

        assertThat(successes(results)).isZero();
        assertThat(count(results, IllegalStateException.class)).isEqualTo(1);
        assertThat(count(results, InvalidResetTokenException.class)).isEqualTo(19);
        verify(transactionService, times(1)).execute(tx);
        assertThat(get(tokenKey(tx))).isNull();
        assertThat(get(resendKey(tx))).isEqualTo("1");
    }

    @Test
    @DisplayName("Luồng đầy đủ: 10 giao dịch (cùng email) chạy send-otp -> verify-otp -> execute đồng thời")
    void fullFlow_manyTransactionsConcurrently() throws Exception {
        List<Transaction> txs = IntStream.range(0, 10).mapToObj(i -> newTx()).toList();

        List<Callable<String>> tasks = txs.stream()
                .map(tx -> (Callable<String>) () -> {
                    service.transactionRequest(tx, email);
                    String otp = otpByRef.get(tx.getReferenceId());
                    String token = service.verifyTransactionOtp(tx, email, otp);
                    service.verifyTransaction(tx, email, token);
                    return token;
                })
                .toList();
        var results = runConcurrently(tasks);

        assertThat(successes(results)).isEqualTo(10);
        assertThat(results.stream().map(Outcome::value).collect(Collectors.toSet())).hasSize(10);
        for (Transaction tx : txs) {
            verify(transactionService, times(1)).execute(tx);
            assertThat(get(otpKey(tx))).isNull();
            assertThat(get(tokenKey(tx))).isNull();
            assertThat(get(resendKey(tx))).isNull();
            assertThat(get(failKey(tx))).isNull();
        }
    }
}
