package com.backend.backend.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import com.backend.backend.auth.customexception.email.ExpiredVerificationCodeException;
import com.backend.backend.auth.customexception.email.InvalidVerificationCodeException;
import com.backend.backend.auth.customexception.email.TooManyResendAttemptsException;
import com.backend.backend.auth.customexception.email.TooManyVerificationAttemptsException;
import com.backend.backend.custom.RateLimiter;

@ExtendWith(MockitoExtension.class)
public class EmailVerificationServiceTest {

    private static final String EMAIL = "an@gmail.com";
    private static final String CODE_KEY = "email_verify:code:" + EMAIL;
    private static final String ATTEMPTS_KEY = "email_verify:attempts:" + EMAIL;
    private static final String RESEND_KEY = "email_verify:resend:attempts:" + EMAIL;
    private static final String REFUND_KEY = "email_verify:refund:attempts:" + EMAIL;

    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private EmailService emailService;
    @Mock
    private RateLimiter rateLimiter;
    @Mock
    private AsyncMailRunner asyncMailRunner;

    @InjectMocks
    private EmailVerificationService service;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "expirationMinutes", 10L);
        ReflectionTestUtils.setField(service, "maxAttempts", 5);
        ReflectionTestUtils.setField(service, "expirationCodeResendMinutes", 60L);
        ReflectionTestUtils.setField(service, "maxResendAttemps", 5);
    }

    // ======================= generateAndSend =======================

    @ParameterizedTest(name = "lần gửi thứ {0}")
    @ValueSource(longs = { 1, 5 })
    @DisplayName("generateAndSend: trong giới hạn gửi lại => lưu mã, reset bộ đếm sai, đưa việc gửi mail cho AsyncMailRunner")
    void generateAndSend_withinLimit_storesCodeAndSchedulesMail(long attempts) {
        when(rateLimiter.hit(RESEND_KEY, 3600L, 5)).thenReturn(attempts);

        service.generateAndSend(EMAIL);

        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(rateLimiter).setExAndDelete(eq(CODE_KEY), code.capture(), eq(Duration.ofMinutes(10)),
                eq(ATTEMPTS_KEY));
        assertThat(code.getValue()).matches("^\\d{6}$");
        verify(asyncMailRunner).run(any(Runnable.class), any());
        verifyNoInteractions(emailService);
    }

    @ParameterizedTest(name = "lần gửi thứ {0}")
    @ValueSource(longs = { 6, 100 })
    @DisplayName("generateAndSend: vượt giới hạn gửi lại => 429, không tạo mã, không gửi mail")
    void generateAndSend_overLimit_throwsAndSendsNothing(long attempts) {
        when(rateLimiter.hit(RESEND_KEY, 3600L, 5)).thenReturn(attempts);

        assertThatThrownBy(() -> service.generateAndSend(EMAIL))
                .isInstanceOf(TooManyResendAttemptsException.class);

        verify(rateLimiter, never()).setExAndDelete(anyString(), anyString(), any(Duration.class), anyString());
        verifyNoInteractions(asyncMailRunner, emailService);
    }

    @Test
    @DisplayName("generateAndSend: 3000 mã đều đúng 6 chữ số, có mã bắt đầu bằng '0' (đệm số 0), không phải giá trị cố định")
    void generateAndSend_codeIsAlwaysSixDigits_withLeadingZeros() {
        for (int i = 0; i < 3000; i++) {
            service.generateAndSend(EMAIL);
        }

        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(rateLimiter, times(3000)).setExAndDelete(eq(CODE_KEY), code.capture(), any(Duration.class),
                eq(ATTEMPTS_KEY));
        List<String> codes = code.getAllValues();
        assertThat(codes).allMatch(c -> c.matches("^\\d{6}$"));
        assertThat(codes).anyMatch(c -> c.startsWith("0"));
        Set<String> distinct = new HashSet<>(codes);
        assertThat(distinct.size()).isGreaterThan(2500);
    }

    @Test
    @DisplayName("generateAndSend: task mail gửi ĐÚNG mã đã lưu tới đúng email")
    @SuppressWarnings({ "rawtypes", "unchecked" })
    void generateAndSend_mailTaskSendsTheStoredCode() {
        service.generateAndSend(EMAIL);

        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(rateLimiter).setExAndDelete(eq(CODE_KEY), code.capture(), any(Duration.class), eq(ATTEMPTS_KEY));
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        ArgumentCaptor<Consumer> onError = ArgumentCaptor.forClass(Consumer.class);
        verify(asyncMailRunner).run(task.capture(), onError.capture());

        task.getValue().run();

        verify(emailService).sendVerificationCode(EMAIL, code.getValue());
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private Consumer captureFailureCallback() {
        service.generateAndSend(EMAIL);
        ArgumentCaptor<Consumer> onError = ArgumentCaptor.forClass(Consumer.class);
        verify(asyncMailRunner).run(any(Runnable.class), onError.capture());
        return onError.getValue();
    }

    @ParameterizedTest(name = "lần hoàn thứ {0}")
    @ValueSource(longs = { 1, 3 })
    @SuppressWarnings({ "rawtypes", "unchecked" })
    @DisplayName("Mail lỗi trong hạn mức hoàn (tối đa 3 lần/24h) => hoàn 1 lượt gửi lại")
    void mailFailure_withinRefundLimit_refundsOneAttempt(long refundHits) {
        Consumer onError = captureFailureCallback();
        when(rateLimiter.hit(REFUND_KEY, 86400L, 3)).thenReturn(refundHits);

        onError.accept(new RuntimeException("smtp down"));

        verify(rateLimiter).refund(RESEND_KEY);
    }

    @ParameterizedTest(name = "lần hoàn thứ {0}")
    @ValueSource(longs = { 4, 10 })
    @SuppressWarnings({ "rawtypes", "unchecked" })
    @DisplayName("Mail lỗi vượt hạn mức hoàn => KHÔNG hoàn lượt")
    void mailFailure_beyondRefundLimit_doesNotRefund(long refundHits) {
        Consumer onError = captureFailureCallback();
        when(rateLimiter.hit(REFUND_KEY, 86400L, 3)).thenReturn(refundHits);

        onError.accept(new RuntimeException("smtp down"));

        verify(rateLimiter, never()).refund(anyString());
    }

    // ======================= verify =======================
    // compareAndDeleteReturnLong: 1 = đúng (đã xoá mã), 0 = sai mã, -1 = không tồn
    // tại/hết hạn

    @Test
    @DisplayName("verify: mã đúng => không ném lỗi, xoá cả mã lẫn bộ đếm sai")
    void verify_correctCode_deletesCodeAndAttempts() {
        when(rateLimiter.hit(ATTEMPTS_KEY, 600L, 1L)).thenReturn(1L);
        when(rateLimiter.compareAndDeleteReturnLong(CODE_KEY, "012345")).thenReturn(1L);

        assertThatCode(() -> service.verify(EMAIL, "012345")).doesNotThrowAnyException();

        // mã được xoá nguyên tử bên trong compareAndDelete, bộ đếm sai xoá qua
        // redisTemplate
        verify(rateLimiter).compareAndDeleteReturnLong(CODE_KEY, "012345");
        verify(stringRedisTemplate).delete(ATTEMPTS_KEY);
        verify(stringRedisTemplate, never()).delete(CODE_KEY);
    }

    @Test
    @DisplayName("verify: lần thử thứ 5 (lần cuối còn được phép) nhập đúng vẫn thành công")
    void verify_fifthAttemptCorrect_stillSucceeds() {
        when(rateLimiter.hit(ATTEMPTS_KEY, 600L, 1L)).thenReturn(5L);
        when(rateLimiter.compareAndDeleteReturnLong(CODE_KEY, "123456")).thenReturn(1L);

        assertThatCode(() -> service.verify(EMAIL, "123456")).doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "lần thử thứ {0}")
    @ValueSource(longs = { 6, 100 })
    @DisplayName("verify: quá số lần thử => 429 kể cả khi nhập đúng, không đọc mã trong Redis")
    void verify_attemptsExceeded_blocksEvenCorrectCode(long attempts) {
        when(rateLimiter.hit(ATTEMPTS_KEY, 600L, 1L)).thenReturn(attempts);

        assertThatThrownBy(() -> service.verify(EMAIL, "123456"))
                .isInstanceOf(TooManyVerificationAttemptsException.class);

        verify(rateLimiter, never()).compareAndDeleteReturnLong(anyString(), anyString());
        verify(stringRedisTemplate, never()).delete(anyString());
    }

    @Test
    @DisplayName("verify: mã không tồn tại hoặc hết hạn => ExpiredVerificationCodeException")
    void verify_missingCode_throwsExpired() {
        when(rateLimiter.hit(ATTEMPTS_KEY, 600L, 1L)).thenReturn(1L);
        when(rateLimiter.compareAndDeleteReturnLong(CODE_KEY, "123456")).thenReturn(-1L);

        assertThatThrownBy(() -> service.verify(EMAIL, "123456"))
                .isInstanceOf(ExpiredVerificationCodeException.class);

        verify(stringRedisTemplate, never()).delete(anyString());
    }

    @Test
    @DisplayName("verify: sai mã => InvalidVerificationCodeException và GIỮ mã để thử lại")
    void verify_wrongCode_throwsInvalidAndKeepsCode() {
        when(rateLimiter.hit(ATTEMPTS_KEY, 600L, 1L)).thenReturn(1L);
        when(rateLimiter.compareAndDeleteReturnLong(CODE_KEY, "654321")).thenReturn(0L);

        assertThatThrownBy(() -> service.verify(EMAIL, "654321"))
                .isInstanceOf(InvalidVerificationCodeException.class);

        verify(stringRedisTemplate, never()).delete(anyString());
    }

    @ParameterizedTest(name = "mã nhập = [{0}]")
    @NullAndEmptySource
    @ValueSource(strings = { " 123456", "123456 ", "12345", "1234567", "123456\n" })
    @DisplayName("verify: mã nhập gần đúng (null, rỗng, thừa/thiếu, khoảng trắng, xuống dòng) đều bị từ chối")
    void verify_nearMissInput_isRejected(String typed) {
        when(rateLimiter.hit(ATTEMPTS_KEY, 600L, 1L)).thenReturn(1L);
        // null bị chặn trước khi gọi Redis nên stub này không phải lúc nào cũng được
        // dùng
        lenient().when(rateLimiter.compareAndDeleteReturnLong(CODE_KEY, typed)).thenReturn(0L);

        assertThatThrownBy(() -> service.verify(EMAIL, typed))
                .isInstanceOf(InvalidVerificationCodeException.class);

        if (typed == null) {
            verify(rateLimiter, never()).compareAndDeleteReturnLong(anyString(), any());
        } else {
            verify(rateLimiter).compareAndDeleteReturnLong(CODE_KEY, typed);
        }
        verify(stringRedisTemplate, never()).delete(anyString());
    }
}