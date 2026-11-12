package com.backend.backend.transaction.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Collection;
import java.util.HexFormat;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

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
import com.backend.backend.transaction.customexception.InvalidTransactionException;
import com.backend.backend.transaction.customexception.NotUserTransaction;
import com.backend.backend.transaction.customexception.TransactionError;

@ExtendWith(MockitoExtension.class)
public class OtpTransactionServiceTest {

    private static final String REF_ID = "ref-123";
    private static final String EMAIL = "user@example.com";
    private static final String SOURCE = "wallet-source";
    private static final String RAW_OTP = "12345678";
    private static final String HASHED_OTP = "hashed-otp";
    private static final String STORED_HASH = "stored-otp-hash";
    private static final String RAW_TOKEN = "raw-token-value";
    private static final String OTP_INVALID_MSG = "Otp đã hết hạn hoặc không hợp lệ";
    private static final String TOKEN_INVALID_MSG = "Token đã hết hạn hoặc không hợp lệ";

    private static final String OTP_KEY = "transaction:otp:ref-123:user@example.com";
    private static final String TOKEN_KEY = "transaction:token:ref-123:user@example.com";
    private static final String FAIL_KEY = "transaction:otp-fail:ref-123:user@example.com";
    private static final String RESEND_KEY = "transaction:resend:ref-123:user@example.com";
    private static final String REFUND_KEY = "transaction:resend-refund:ref-123:user@example.com";

    private static final long FAIL_WINDOW_SECONDS = 10 * 60L * 2L;

    @Mock
    private StringRedisTemplate redis;
    @Mock
    private ValueOperations<String, String> valueOps;
    @Mock
    private OtpService otpService;
    @Mock
    private EmailService emailService;
    @Mock
    private RateLimiter rateLimiter;
    @Mock
    private TransactionService transactionService;
    @Mock
    private AsyncMailRunner asyncMailRunner;

    @InjectMocks
    private OtpTransactionService service;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "otpTtlMinutes", 10);
        ReflectionTestUtils.setField(service, "maxOtpAttempts", 5);
        ReflectionTestUtils.setField(service, "tokenTtlMinutes", 10);
        ReflectionTestUtils.setField(service, "maxResendAttempts", 5);
        ReflectionTestUtils.setField(service, "resendLockHours", 24L);
        lenient().when(redis.opsForValue()).thenReturn(valueOps);
    }

    private static Transaction pendingTx() {
        return Transaction.builder()
                .referenceId(REF_ID)
                .type(TransactionType.TRANSFER)
                .status(TransactionStatus.PENDING)
                .sourceWalletId(SOURCE)
                .destinationWalletId("wallet-dest")
                .amount(new BigDecimal("100000"))
                .description("test")
                .build();
    }

    private static String sha256Hex(String value) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private void givenValidRequest() {
        when(transactionService.isUserWallet(SOURCE, EMAIL)).thenReturn(true);
        when(rateLimiter.hit(RESEND_KEY, 86400L, 5)).thenReturn(1L);
        when(otpService.generate()).thenReturn(RAW_OTP);
        when(otpService.hash(RAW_OTP)).thenReturn(HASHED_OTP);
    }

    private void verifyNoTokenIssued() {
        verify(rateLimiter, never()).setExAndDelete(eq(TOKEN_KEY), anyString(), any(Duration.class),
                anyString());
    }

    @Test
    void transactionRequest_whenTransactionIsNull_shouldThrowAndTouchNothing() {
        assertThatThrownBy(() -> service.transactionRequest(null, EMAIL))
                .isInstanceOf(InvalidTransactionException.class)
                .hasMessage("Giao dịch phải được đính kèm");

        verifyNoInteractions(transactionService, rateLimiter, otpService, asyncMailRunner, redis);
    }

    @ParameterizedTest(name = "status = {0}")
    @NullSource
    @EnumSource(value = TransactionStatus.class, names = { "SUCCESS", "FAILED" })
    void transactionRequest_whenStatusIsNotPending_shouldThrowAndTouchNothing(TransactionStatus status) {
        Transaction tx = pendingTx();
        tx.setStatus(status);

        assertThatThrownBy(() -> service.transactionRequest(tx, EMAIL))
                .isInstanceOf(InvalidTransactionException.class);

        verifyNoInteractions(rateLimiter, otpService, asyncMailRunner, redis);
    }

    @Test
    void transactionRequest_whenEmailIsNotWalletOwner_shouldThrowAndNotSendOtp() {
        when(transactionService.isUserWallet(SOURCE, EMAIL)).thenReturn(false);

        assertThatThrownBy(() -> service.transactionRequest(pendingTx(), EMAIL))
                .isInstanceOf(NotUserTransaction.class);

        verifyNoInteractions(rateLimiter, otpService, asyncMailRunner, redis);
    }

    @ParameterizedTest(name = "lần gửi thứ {0}")
    @ValueSource(longs = { 1, 5 })
    void transactionRequest_whenResendAttemptsWithinLimit_shouldSendOtp(long attempts) {
        when(transactionService.isUserWallet(SOURCE, EMAIL)).thenReturn(true);
        when(rateLimiter.hit(RESEND_KEY, 86400L, 5)).thenReturn(attempts);
        when(otpService.generate()).thenReturn(RAW_OTP);
        when(otpService.hash(RAW_OTP)).thenReturn(HASHED_OTP);

        assertThatCode(() -> service.transactionRequest(pendingTx(), EMAIL)).doesNotThrowAnyException();

        verify(asyncMailRunner).run(any(), any());
    }

    @ParameterizedTest(name = "lần gửi thứ {0}")
    @ValueSource(longs = { 6, 100 })
    void transactionRequest_whenResendAttemptsExceeded_shouldThrowAndSendNothing(long attempts) {
        when(transactionService.isUserWallet(SOURCE, EMAIL)).thenReturn(true);
        when(rateLimiter.hit(RESEND_KEY, 86400L, 5)).thenReturn(attempts);

        assertThatThrownBy(() -> service.transactionRequest(pendingTx(), EMAIL))
                .isInstanceOf(TooManyResendAttemptsException.class);

        verifyNoInteractions(otpService, asyncMailRunner, emailService);
        verify(rateLimiter, never()).setExAndDelete(anyString(), anyString(), any(Duration.class),
                anyString());
    }

    @Test
    @SuppressWarnings({ "rawtypes", "unchecked" })
    void transactionRequest_whenValid_shouldStoreHashedOtpResetFailCounterAndSendRawOtpByMail() {
        Transaction tx = pendingTx();
        givenValidRequest();

        service.transactionRequest(tx, EMAIL);

        verify(rateLimiter).setExAndDelete(OTP_KEY, HASHED_OTP, Duration.ofMinutes(10), FAIL_KEY);
        verify(rateLimiter, never()).setExAndDelete(anyString(), eq(RAW_OTP), any(Duration.class),
                anyString());

        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        ArgumentCaptor<Consumer> onError = ArgumentCaptor.forClass(Consumer.class);
        verify(asyncMailRunner).run(task.capture(), onError.capture());
        verifyNoInteractions(emailService);

        task.getValue().run();
        verify(emailService).sendTransactionOtp(EMAIL, RAW_OTP, 10, tx);
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private Consumer captureMailErrorCallback() {
        givenValidRequest();
        service.transactionRequest(pendingTx(), EMAIL);
        ArgumentCaptor<Consumer> onError = ArgumentCaptor.forClass(Consumer.class);
        verify(asyncMailRunner).run(any(), onError.capture());
        return onError.getValue();
    }

    @ParameterizedTest(name = "lần hoàn thứ {0}")
    @ValueSource(longs = { 1, 3 })
    @SuppressWarnings({ "rawtypes", "unchecked" })
    void transactionRequest_whenMailFailsWithinRefundLimit_shouldRefundResendAttempt(long refundHits) {
        Consumer onError = captureMailErrorCallback();
        when(rateLimiter.hit(REFUND_KEY, 86400L, 3)).thenReturn(refundHits);

        onError.accept(new RuntimeException("smtp down"));

        verify(rateLimiter).refund(RESEND_KEY);
    }

    @ParameterizedTest(name = "lần hoàn thứ {0}")
    @ValueSource(longs = { 4, 10 })
    @SuppressWarnings({ "rawtypes", "unchecked" })
    void transactionRequest_whenMailFailsBeyondRefundLimit_shouldNotRefund(long refundHits) {
        Consumer onError = captureMailErrorCallback();
        when(rateLimiter.hit(REFUND_KEY, 86400L, 3)).thenReturn(refundHits);

        onError.accept(new RuntimeException("smtp down"));

        verify(rateLimiter, never()).refund(anyString());
    }

    @Test
    void verifyTransactionOtp_whenTransactionIsNull_shouldThrowAndTouchNothing() {
        assertThatThrownBy(() -> service.verifyTransactionOtp(null, EMAIL, RAW_OTP))
                .isInstanceOf(InvalidTransactionException.class)
                .hasMessage("Giao dịch phải được đính kèm");

        verifyNoInteractions(redis, rateLimiter, otpService, transactionService);
    }

    @ParameterizedTest(name = "otp = [{0}]")
    @NullAndEmptySource
    @ValueSource(strings = { " ", "\t" })
    void verifyTransactionOtp_whenOtpIsBlank_shouldThrowAndTouchNothing(String otp) {
        assertThatThrownBy(() -> service.verifyTransactionOtp(pendingTx(), EMAIL, otp))
                .isInstanceOf(InvalidOtpException.class)
                .hasMessage("Xác thực phải gửi kèm Otp");

        verifyNoInteractions(redis, rateLimiter, otpService);
    }

    @ParameterizedTest(name = "status = {0}")
    @NullSource
    @EnumSource(value = TransactionStatus.class, names = { "SUCCESS", "FAILED" })
    void verifyTransactionOtp_whenStatusIsNotPending_shouldThrowAndTouchNothing(TransactionStatus status) {
        Transaction tx = pendingTx();
        tx.setStatus(status);

        assertThatThrownBy(() -> service.verifyTransactionOtp(tx, EMAIL, RAW_OTP))
                .isInstanceOf(InvalidTransactionException.class);

        verifyNoInteractions(redis, rateLimiter, otpService);
    }

    @Test
    void verifyTransactionOtp_whenEmailIsNotWalletOwner_shouldThrowAndNotCountAttempt() {
        when(transactionService.isUserWallet(SOURCE, EMAIL)).thenReturn(false);

        assertThatThrownBy(() -> service.verifyTransactionOtp(pendingTx(), EMAIL, RAW_OTP))
                .isInstanceOf(NotUserTransaction.class);

        verifyNoInteractions(redis, rateLimiter, otpService);
    }

    @Test
    void verifyTransactionOtp_whenOtpCorrect_shouldIssueTokenAndStoreOnlyItsHash() throws Exception {
        when(transactionService.isUserWallet(SOURCE, EMAIL)).thenReturn(true);
        when(valueOps.get(OTP_KEY)).thenReturn(STORED_HASH);
        when(rateLimiter.hit(FAIL_KEY, FAIL_WINDOW_SECONDS, 5)).thenReturn(1L);
        when(otpService.matches(RAW_OTP, STORED_HASH)).thenReturn(true);
        when(rateLimiter.compareAndDelete(OTP_KEY, STORED_HASH)).thenReturn(true);

        String token = service.verifyTransactionOtp(pendingTx(), EMAIL, RAW_OTP);

        assertThat(token).isNotBlank();

        ArgumentCaptor<String> storedValue = ArgumentCaptor.forClass(String.class);
        verify(rateLimiter).setExAndDelete(eq(TOKEN_KEY), storedValue.capture(),
                eq(Duration.ofMinutes(10)), eq(FAIL_KEY));
        assertThat(storedValue.getValue()).isNotEqualTo(token);
        assertThat(storedValue.getValue()).isEqualTo(sha256Hex(token));
    }

    @Test
    void verifyTransactionOtp_whenCalledTwiceWithValidOtp_shouldIssueDifferentTokens() {
        when(transactionService.isUserWallet(SOURCE, EMAIL)).thenReturn(true);
        when(valueOps.get(OTP_KEY)).thenReturn(STORED_HASH);
        when(rateLimiter.hit(FAIL_KEY, FAIL_WINDOW_SECONDS, 5)).thenReturn(1L);
        when(otpService.matches(RAW_OTP, STORED_HASH)).thenReturn(true);
        when(rateLimiter.compareAndDelete(OTP_KEY, STORED_HASH)).thenReturn(true);

        String first = service.verifyTransactionOtp(pendingTx(), EMAIL, RAW_OTP);
        String second = service.verifyTransactionOtp(pendingTx(), EMAIL, RAW_OTP);

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void verifyTransactionOtp_whenFifthAttemptIsCorrect_shouldStillSucceed() {
        when(transactionService.isUserWallet(SOURCE, EMAIL)).thenReturn(true);
        when(valueOps.get(OTP_KEY)).thenReturn(STORED_HASH);
        when(rateLimiter.hit(FAIL_KEY, FAIL_WINDOW_SECONDS, 5)).thenReturn(5L);
        when(otpService.matches(RAW_OTP, STORED_HASH)).thenReturn(true);
        when(rateLimiter.compareAndDelete(OTP_KEY, STORED_HASH)).thenReturn(true);

        assertThatCode(() -> service.verifyTransactionOtp(pendingTx(), EMAIL, RAW_OTP))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "lần thử thứ {0}")
    @ValueSource(longs = { 6, 100 })
    void verifyTransactionOtp_whenAttemptsExceeded_shouldBlockEvenIfOtpIsCorrectAndInvalidateOtp(long attempts) {
        when(transactionService.isUserWallet(SOURCE, EMAIL)).thenReturn(true);
        when(valueOps.get(OTP_KEY)).thenReturn(STORED_HASH);
        when(rateLimiter.hit(FAIL_KEY, FAIL_WINDOW_SECONDS, 5)).thenReturn(attempts);

        assertThatThrownBy(() -> service.verifyTransactionOtp(pendingTx(), EMAIL, RAW_OTP))
                .isInstanceOf(ManyOtpException.class);

        verify(redis).delete(OTP_KEY);
        verifyNoInteractions(otpService);
        verify(rateLimiter, never()).compareAndDelete(anyString(), anyString());
        verifyNoTokenIssued();
    }

    @Test
    void verifyTransactionOtp_whenOtpExpiredOrMissing_shouldThrowInvalidOtp() {
        when(transactionService.isUserWallet(SOURCE, EMAIL)).thenReturn(true);
        when(valueOps.get(OTP_KEY)).thenReturn(null);
        when(rateLimiter.hit(FAIL_KEY, FAIL_WINDOW_SECONDS, 5)).thenReturn(1L);

        assertThatThrownBy(() -> service.verifyTransactionOtp(pendingTx(), EMAIL, RAW_OTP))
                .isInstanceOf(InvalidOtpException.class)
                .hasMessage(OTP_INVALID_MSG);

        verifyNoInteractions(otpService);
        verify(rateLimiter, never()).compareAndDelete(anyString(), anyString());
        verifyNoTokenIssued();
    }

    @Test
    void verifyTransactionOtp_whenOtpWrong_shouldThrowInvalidOtpAndKeepOtpForNextTry() {
        when(transactionService.isUserWallet(SOURCE, EMAIL)).thenReturn(true);
        when(valueOps.get(OTP_KEY)).thenReturn(STORED_HASH);
        when(rateLimiter.hit(FAIL_KEY, FAIL_WINDOW_SECONDS, 5)).thenReturn(1L);
        when(otpService.matches("00000000", STORED_HASH)).thenReturn(false);

        assertThatThrownBy(() -> service.verifyTransactionOtp(pendingTx(), EMAIL, "00000000"))
                .isInstanceOf(InvalidOtpException.class)
                .hasMessage(OTP_INVALID_MSG);

        verify(rateLimiter, never()).compareAndDelete(anyString(), anyString());
        verify(redis, never()).delete(anyString());
        verifyNoTokenIssued();
    }

    @Test
    void verifyTransactionOtp_whenOtpConsumedByConcurrentRequest_shouldThrowInvalidOtpAndIssueNoToken() {
        when(transactionService.isUserWallet(SOURCE, EMAIL)).thenReturn(true);
        when(valueOps.get(OTP_KEY)).thenReturn(STORED_HASH);
        when(rateLimiter.hit(FAIL_KEY, FAIL_WINDOW_SECONDS, 5)).thenReturn(1L);
        when(otpService.matches(RAW_OTP, STORED_HASH)).thenReturn(true);
        when(rateLimiter.compareAndDelete(OTP_KEY, STORED_HASH)).thenReturn(false);

        assertThatThrownBy(() -> service.verifyTransactionOtp(pendingTx(), EMAIL, RAW_OTP))
                .isInstanceOf(InvalidOtpException.class)
                .hasMessage(OTP_INVALID_MSG);

        verify(rateLimiter, times(1)).compareAndDelete(OTP_KEY, STORED_HASH);
        verifyNoTokenIssued();
    }

    @Test
    void verifyTransactionOtp_whenRedisReadFails_shouldPropagateAndIssueNoToken() {
        when(transactionService.isUserWallet(SOURCE, EMAIL)).thenReturn(true);
        when(valueOps.get(OTP_KEY)).thenThrow(new RedisConnectionFailureException("redis down"));

        assertThatThrownBy(() -> service.verifyTransactionOtp(pendingTx(), EMAIL, RAW_OTP))
                .isInstanceOf(RedisConnectionFailureException.class);

        verifyNoInteractions(otpService);
        verifyNoTokenIssued();
    }

    @Test
    void verifyTransaction_whenTransactionIsNull_shouldThrowAndNotExecute() {
        assertThatThrownBy(() -> service.verifyTransaction(null, EMAIL, RAW_TOKEN))
                .isInstanceOf(InvalidTransactionException.class)
                .hasMessage("Giao dịch phải được đính kèm");

        verifyNoInteractions(redis, rateLimiter, transactionService);
    }

    @ParameterizedTest(name = "token = [{0}]")
    @NullAndEmptySource
    @ValueSource(strings = { " ", "\t" })
    void verifyTransaction_whenTokenIsBlank_shouldThrowAndNotExecute(String token) {
        assertThatThrownBy(() -> service.verifyTransaction(pendingTx(), EMAIL, token))
                .isInstanceOf(InvalidResetTokenException.class)
                .hasMessage("Phải gửi kèm token xác thực");

        verifyNoInteractions(redis, rateLimiter, transactionService);
    }

    @ParameterizedTest(name = "status = {0}")
    @NullSource
    @EnumSource(value = TransactionStatus.class, names = { "SUCCESS", "FAILED" })
    void verifyTransaction_whenStatusIsNotPending_shouldThrowAndNotExecute(TransactionStatus status) {
        Transaction tx = pendingTx();
        tx.setStatus(status);

        assertThatThrownBy(() -> service.verifyTransaction(tx, EMAIL, RAW_TOKEN))
                .isInstanceOf(InvalidTransactionException.class);

        verifyNoInteractions(redis, rateLimiter);
        verify(transactionService, never()).execute(any());
    }

    @Test
    void verifyTransaction_whenEmailIsNotWalletOwner_shouldThrowAndNotExecute() {
        when(transactionService.isUserWallet(SOURCE, EMAIL)).thenReturn(false);

        assertThatThrownBy(() -> service.verifyTransaction(pendingTx(), EMAIL, RAW_TOKEN))
                .isInstanceOf(NotUserTransaction.class);

        verifyNoInteractions(redis, rateLimiter);
        verify(transactionService, never()).execute(any());
    }

    @Test
    void verifyTransaction_whenTokenValid_shouldConsumeTokenThenExecuteThenCleanUp() throws Exception {
        Transaction tx = pendingTx();
        String storedHash = sha256Hex(RAW_TOKEN);
        when(transactionService.isUserWallet(SOURCE, EMAIL)).thenReturn(true);
        when(valueOps.get(TOKEN_KEY)).thenReturn(storedHash);
        when(rateLimiter.compareAndDelete(TOKEN_KEY, storedHash)).thenReturn(true);

        assertThatCode(() -> service.verifyTransaction(tx, EMAIL, RAW_TOKEN)).doesNotThrowAnyException();

        InOrder order = inOrder(rateLimiter, transactionService, redis);
        order.verify(rateLimiter).compareAndDelete(TOKEN_KEY, storedHash);
        order.verify(transactionService).execute(tx);
        order.verify(redis).delete(ArgumentMatchers.<Collection<String>>any());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<String>> keys = ArgumentCaptor.forClass(Collection.class);
        verify(redis).delete(keys.capture());
        assertThat(keys.getValue())
                .containsExactlyInAnyOrder(RESEND_KEY, OTP_KEY, FAIL_KEY, REFUND_KEY);
    }

    @Test
    void verifyTransaction_whenRedisCleanUpFails_shouldStillSucceedBecauseMoneyAlreadyMoved() throws Exception {
        Transaction tx = pendingTx();
        String storedHash = sha256Hex(RAW_TOKEN);
        when(transactionService.isUserWallet(SOURCE, EMAIL)).thenReturn(true);
        when(valueOps.get(TOKEN_KEY)).thenReturn(storedHash);
        when(rateLimiter.compareAndDelete(TOKEN_KEY, storedHash)).thenReturn(true);
        when(redis.delete(ArgumentMatchers.<Collection<String>>any()))
                .thenThrow(new RedisConnectionFailureException("redis down"));

        assertThatCode(() -> service.verifyTransaction(tx, EMAIL, RAW_TOKEN)).doesNotThrowAnyException();

        verify(transactionService).execute(tx);
    }

    @Test
    void verifyTransaction_whenTokenExpiredOrMissing_shouldThrowAndNotExecute() {
        when(transactionService.isUserWallet(SOURCE, EMAIL)).thenReturn(true);
        when(valueOps.get(TOKEN_KEY)).thenReturn(null);

        assertThatThrownBy(() -> service.verifyTransaction(pendingTx(), EMAIL, RAW_TOKEN))
                .isInstanceOf(InvalidResetTokenException.class)
                .hasMessage(TOKEN_INVALID_MSG);

        verify(rateLimiter, never()).compareAndDelete(anyString(), anyString());
        verify(transactionService, never()).execute(any());
    }

    @Test
    void verifyTransaction_whenTokenWrong_shouldThrowKeepTokenAndNotExecute() throws Exception {
        when(transactionService.isUserWallet(SOURCE, EMAIL)).thenReturn(true);
        when(valueOps.get(TOKEN_KEY)).thenReturn(sha256Hex(RAW_TOKEN));

        assertThatThrownBy(() -> service.verifyTransaction(pendingTx(), EMAIL, "another-token"))
                .isInstanceOf(InvalidResetTokenException.class)
                .hasMessage(TOKEN_INVALID_MSG);

        verify(rateLimiter, never()).compareAndDelete(anyString(), anyString());
        verify(transactionService, never()).execute(any());
    }

    @Test
    void verifyTransaction_whenTokenUsedByConcurrentRequest_shouldThrowAndNotExecuteTwice() throws Exception {
        String storedHash = sha256Hex(RAW_TOKEN);
        when(transactionService.isUserWallet(SOURCE, EMAIL)).thenReturn(true);
        when(valueOps.get(TOKEN_KEY)).thenReturn(storedHash);
        when(rateLimiter.compareAndDelete(TOKEN_KEY, storedHash)).thenReturn(false);

        assertThatThrownBy(() -> service.verifyTransaction(pendingTx(), EMAIL, RAW_TOKEN))
                .isInstanceOf(InvalidResetTokenException.class)
                .hasMessage(TOKEN_INVALID_MSG);

        verify(transactionService, never()).execute(any());
        verify(redis, never()).delete(ArgumentMatchers.<Collection<String>>any());
    }

    @Test
    void verifyTransaction_whenExecuteFails_shouldPropagateConsumeTokenAndKeepRateLimitKeys() throws Exception {
        Transaction tx = pendingTx();
        String storedHash = sha256Hex(RAW_TOKEN);
        when(transactionService.isUserWallet(SOURCE, EMAIL)).thenReturn(true);
        when(valueOps.get(TOKEN_KEY)).thenReturn(storedHash);
        when(rateLimiter.compareAndDelete(TOKEN_KEY, storedHash)).thenReturn(true);
        doThrow(new TransactionError("Giao dịch thực hiện không thành công"))
                .when(transactionService).execute(tx);

        assertThatThrownBy(() -> service.verifyTransaction(tx, EMAIL, RAW_TOKEN))
                .isInstanceOf(TransactionError.class)
                .hasMessage("Giao dịch thực hiện không thành công");

        verify(rateLimiter).compareAndDelete(TOKEN_KEY, storedHash);
        verify(redis, never()).delete(ArgumentMatchers.<Collection<String>>any());
    }

    @Test
    void verifyTransaction_whenRedisReadFails_shouldPropagateAndNotExecute() {
        when(transactionService.isUserWallet(SOURCE, EMAIL)).thenReturn(true);
        when(valueOps.get(TOKEN_KEY)).thenThrow(new RedisConnectionFailureException("redis down"));

        assertThatThrownBy(() -> service.verifyTransaction(pendingTx(), EMAIL, RAW_TOKEN))
                .isInstanceOf(RedisConnectionFailureException.class);

        verify(transactionService, never()).execute(any());
    }

    @Test
    void issuedToken_shouldBeAcceptedByVerifyTransaction() {
        Transaction tx = pendingTx();
        when(transactionService.isUserWallet(SOURCE, EMAIL)).thenReturn(true);
        when(valueOps.get(OTP_KEY)).thenReturn(STORED_HASH);
        when(rateLimiter.hit(FAIL_KEY, FAIL_WINDOW_SECONDS, 5)).thenReturn(1L);
        when(otpService.matches(RAW_OTP, STORED_HASH)).thenReturn(true);
        when(rateLimiter.compareAndDelete(OTP_KEY, STORED_HASH)).thenReturn(true);

        String token = service.verifyTransactionOtp(tx, EMAIL, RAW_OTP);

        ArgumentCaptor<String> storedTokenHash = ArgumentCaptor.forClass(String.class);
        verify(rateLimiter).setExAndDelete(eq(TOKEN_KEY), storedTokenHash.capture(),
                eq(Duration.ofMinutes(10)), eq(FAIL_KEY));
        when(valueOps.get(TOKEN_KEY)).thenReturn(storedTokenHash.getValue());
        when(rateLimiter.compareAndDelete(TOKEN_KEY, storedTokenHash.getValue())).thenReturn(true);

        service.verifyTransaction(tx, EMAIL, token);

        verify(transactionService).execute(tx);
    }
}
