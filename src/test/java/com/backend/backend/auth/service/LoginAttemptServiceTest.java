package com.backend.backend.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.util.ReflectionTestUtils;

import com.backend.backend.auth.customexception.authentication.LoginLockedException;

@ExtendWith(MockitoExtension.class)
public class LoginAttemptServiceTest {

    private static final String EMAIL = "an@gmail.com";
    private static final String FAIL_KEY = "login:fail:" + EMAIL;
    private static final String LOCK_KEY = "login:lock:" + EMAIL;

    @Mock
    private StringRedisTemplate redis;
    @Mock
    private AsyncMailRunner asyncMailRunner;
    @Mock
    private EmailService emailService;

    @InjectMocks
    private LoginAttemptService service;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "loginAttemptLv1", 5);
        ReflectionTestUtils.setField(service, "loginAttemptLv2", 10);
        ReflectionTestUtils.setField(service, "loginAttemptLv3", 15);
        ReflectionTestUtils.setField(service, "loginLockLv1", 15);
        ReflectionTestUtils.setField(service, "loginLockLv2", 60);
        ReflectionTestUtils.setField(service, "loginLockLv3", 43200);
        ReflectionTestUtils.setField(service, "attemptsDuration", 43100);
    }

    @Test
    @DisplayName("@PostConstruct validateLockConfig: khóa mức 3 ngắn hơn attemptsDuration => fail-fast khi khởi động")
    void validateLockConfig_whenLv3ShorterThanDuration_failsContextStartup() {
        new ApplicationContextRunner()
                .withBean(StringRedisTemplate.class, () -> redis)
                .withBean(AsyncMailRunner.class, () -> asyncMailRunner)
                .withBean(EmailService.class, () -> emailService)
                .withUserConfiguration(LoginAttemptService.class)
                .withPropertyValues(
                        "app.login.max-attempts-1=5",
                        "app.login.max-attempts-2=10",
                        "app.login.max-attempts-3=15",
                        "app.login.expiration-minutes-1=15",
                        "app.login.expiration-minutes-2=60",
                        "app.login.expiration-minutes-3=100",
                        "app.login.attempt.duration-minutes=200")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    @DisplayName("isBlocked: chuẩn hoá identifier và xử lý an toàn khi Redis trả true/false/null")
    void isBlocked_normalizesAndHandlesBooleanResults() {
        when(redis.hasKey(LOCK_KEY)).thenReturn(Boolean.TRUE);
        assertThat(service.isBlocked("  AN@GMAIL.COM  ")).isTrue();

        when(redis.hasKey(LOCK_KEY)).thenReturn(Boolean.FALSE);
        assertThat(service.isBlocked(EMAIL)).isFalse();

        when(redis.hasKey("login:lock:")).thenReturn(null);
        assertThat(service.isBlocked(null)).isFalse();
    }

    @Test
    @DisplayName("getRemainingLockSeconds: trả 0 khi ttl null hoặc âm (-1/-2), trả đúng giá trị khi > 0")
    void getRemainingLockSeconds_handlesMissingAndPositiveTtl() {
        when(redis.getExpire(LOCK_KEY, TimeUnit.SECONDS)).thenReturn(null);
        assertThat(service.getRemainingLockSeconds(EMAIL)).isZero();

        when(redis.getExpire(LOCK_KEY, TimeUnit.SECONDS)).thenReturn(-2L);
        assertThat(service.getRemainingLockSeconds(EMAIL)).isZero();

        when(redis.getExpire(LOCK_KEY, TimeUnit.SECONDS)).thenReturn(-1L);
        assertThat(service.getRemainingLockSeconds(EMAIL)).isZero();

        when(redis.getExpire(LOCK_KEY, TimeUnit.SECONDS)).thenReturn(89L);
        assertThat(service.getRemainingLockSeconds("  AN@GMAIL.COM ")).isEqualTo(89L);
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("loginAttemptFailed: status 0/1/2 trả về đúng boolean, không gửi mail; status null/rỗng trả false")
    void loginAttemptFailed_nonLv3Statuses() {
        when(redis.execute(any(RedisScript.class), eq(List.of(FAIL_KEY, LOCK_KEY)),
                any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of(0L, 1L), List.of(1L, 6L), List.of(2L, 6L), null, List.of());

        assertThat(service.loginAttemptFailed(EMAIL)).isFalse();
        assertThat(service.loginAttemptFailed(EMAIL)).isTrue();
        assertThat(service.loginAttemptFailed(EMAIL)).isTrue();
        assertThat(service.loginAttemptFailed(EMAIL)).isFalse();
        assertThat(service.loginAttemptFailed(EMAIL)).isFalse();

        verifyNoInteractions(asyncMailRunner, emailService);
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("loginAttemptFailed: status 3 gửi cảnh báo qua AsyncMailRunner rồi ném LoginLockedException; status 4 chỉ ném lỗi")
    void loginAttemptFailed_lv3AndLockedBefore_sendMailOnlyOnce() {
        when(redis.execute(any(RedisScript.class), eq(List.of(FAIL_KEY, LOCK_KEY)),
                any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of(3L, 16L), List.of(4L, 16L));

        assertThatThrownBy(() -> service.loginAttemptFailed("  AN@GMAIL.COM  "))
                .isInstanceOf(LoginLockedException.class)
                .hasMessageContaining("đặt lại mật khẩu");

        ArgumentCaptor<Runnable> mailTask = ArgumentCaptor.forClass(Runnable.class);
        verify(asyncMailRunner).run(mailTask.capture());
        mailTask.getValue().run();
        verify(emailService).sendTryToLoginWarning("  AN@GMAIL.COM  ");

        assertThatThrownBy(() -> service.loginAttemptFailed(EMAIL))
                .isInstanceOf(LoginLockedException.class);
        verifyNoMoreInteractions(asyncMailRunner, emailService);
    }

    @Test
    @DisplayName("loginSucceeded: xoá đồng thời cả failKey và lockKey đã chuẩn hoá")
    void loginSucceeded_deletesNormalizedKeys() {
        service.loginSucceeded("  AN@GMAIL.COM  ");

        verify(redis).delete(List.of(FAIL_KEY, LOCK_KEY));
    }
}
