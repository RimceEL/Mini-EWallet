package com.backend.backend.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import com.backend.backend.auth.customexception.authentication.InvalidRegisterRequest;
import com.backend.backend.auth.customexception.otp.InvalidOtpException;
import com.backend.backend.auth.customexception.otp.InvalidResetTokenException;
import com.backend.backend.auth.model.Role;
import com.backend.backend.auth.model.User;
import com.backend.backend.auth.repository.AuthRepository;
import com.backend.backend.custom.RateLimiter;

@ExtendWith(MockitoExtension.class)
public class PasswordResetServiceTest {

    private static final String EMAIL = "an@gmail.com";
    private static final String OTP_KEY = "pwreset:otp:" + EMAIL;
    private static final String OTP_FAIL_KEY = "pwreset:otp-fail:" + EMAIL;
    private static final String TOKEN_KEY = "pwreset:token:" + EMAIL;
    private static final String RESEND_KEY = "pwreset:resend:" + EMAIL;

    @Mock
    private StringRedisTemplate redis;
    @Mock
    private ValueOperations<String, String> valueOps;
    @Mock
    private AuthRepository authRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private OtpService otpService;
    @Mock
    private EmailService emailService;
    @Mock
    private RefreshTokenService refreshTokenService;
    @Mock
    private LoginAttemptService loginAttemptService;
    @Mock
    private RateLimiter rateLimiter;
    @Mock
    private AsyncMailRunner asyncMailRunner;

    @InjectMocks
    private PasswordResetService service;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "maxResendAttempts", 5);
        ReflectionTestUtils.setField(service, "resendLockHours", 24L);
        ReflectionTestUtils.setField(service, "otpTtlMinutes", 10);
        ReflectionTestUtils.setField(service, "tokenTtlMinutes", 10);
        ReflectionTestUtils.setField(service, "maxOtpAttempts", 5);
        lenient().when(redis.opsForValue()).thenReturn(valueOps);
    }

    private static String sha256(String raw) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("requestReset: email không hợp lệ => ném InvalidRegisterRequest, không gọi rateLimiter hay repo")
    void requestReset_invalidEmail_throwsAndTouchesNothing() {
        assertThatThrownBy(() -> service.requestReset("bad-email"))
                .isInstanceOf(InvalidRegisterRequest.class);

        verifyNoInteractions(rateLimiter, authRepository, otpService, asyncMailRunner);
    }

    @Test
    @DisplayName("requestReset: email không tồn tại => vẫn tính rate limit nhưng không tạo OTP và không gửi mail")
    void requestReset_unknownEmail_hitsRateLimitAndReturnsSilently() {
        when(rateLimiter.hit(RESEND_KEY, 24 * 3600L, 5)).thenReturn(1L);
        when(authRepository.findUserByEmail(EMAIL)).thenReturn(Optional.empty());

        service.requestReset(EMAIL);

        verify(rateLimiter).hit(RESEND_KEY, 24 * 3600L, 5);
        verifyNoInteractions(otpService, asyncMailRunner, emailService);
        verify(rateLimiter, never()).setExAndDelete(anyString(), anyString(), any(Duration.class), anyString());
    }

    @Test
    @DisplayName("verifyOtp: compareAndDelete trả false (OTP vừa bị request khác tiêu thụ hoặc ghi đè) => ném InvalidOtpException, không cấp token")
    void verifyOtp_whenCompareAndDeleteReturnsFalse_throwsAndIssuesNoToken() {
        when(valueOps.get(OTP_KEY)).thenReturn("stored-hash");
        when(rateLimiter.hit(OTP_FAIL_KEY, 1200L, 5)).thenReturn(1L);
        when(otpService.matches("12345678", "stored-hash")).thenReturn(true);
        when(rateLimiter.compareAndDelete(OTP_KEY, "stored-hash")).thenReturn(false);

        assertThatThrownBy(() -> service.verifyOtp(EMAIL, "12345678"))
                .isInstanceOf(InvalidOtpException.class);

        verify(rateLimiter, never()).setExAndDelete(eq(TOKEN_KEY), anyString(), any(Duration.class), anyString());
    }

    @Test
    @DisplayName("verifyOtp: truyền OTP null => tăng bộ đếm sai và ném InvalidOtpException")
    void verifyOtp_nullOtp_countsAttemptAndThrowsInvalidOtp() {
        when(valueOps.get(OTP_KEY)).thenReturn("stored-hash");
        when(rateLimiter.hit(OTP_FAIL_KEY, 1200L, 5)).thenReturn(1L);
        when(otpService.matches(null, "stored-hash")).thenReturn(false);

        assertThatThrownBy(() -> service.verifyOtp(EMAIL, null))
                .isInstanceOf(InvalidOtpException.class);

        verify(rateLimiter).hit(eq(OTP_FAIL_KEY), anyLong(), eq(5L));
        verify(rateLimiter, never()).compareAndDelete(anyString(), anyString());
    }

    @Test
    @DisplayName("resetPassword: user bị xoá giữa bước verifyOtp và resetPassword => tiêu thụ token rồi ném InvalidResetTokenException")
    void resetPassword_whenUserDeletedMidFlow_throwsInvalidResetToken() throws Exception {
        String rawToken = "valid-token";
        String tokenHash = sha256(rawToken);
        when(valueOps.get(TOKEN_KEY)).thenReturn(tokenHash);
        when(rateLimiter.compareAndDelete(TOKEN_KEY, tokenHash)).thenReturn(true);
        when(authRepository.findUserByEmail(EMAIL)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resetPassword(EMAIL, rawToken, "MatKhauMoiDuDai!1"))
                .isInstanceOf(InvalidResetTokenException.class);

        verify(authRepository, never()).updateUserPassword(any());
        verifyNoInteractions(refreshTokenService, loginAttemptService, asyncMailRunner);
    }

    @Test
    @DisplayName("resetPassword: compareAndDelete trả false => không update mật khẩu, không thu hồi phiên")
    void resetPassword_whenCompareAndDeleteFails_doesNotChangePassword() throws Exception {
        String rawToken = "valid-token";
        String tokenHash = sha256(rawToken);
        when(valueOps.get(TOKEN_KEY)).thenReturn(tokenHash);
        when(rateLimiter.compareAndDelete(TOKEN_KEY, tokenHash)).thenReturn(false);

        assertThatThrownBy(() -> service.resetPassword(EMAIL, rawToken, "MatKhauMoiDuDai!1"))
                .isInstanceOf(InvalidResetTokenException.class);

        verifyNoInteractions(authRepository, passwordEncoder, refreshTokenService, loginAttemptService, asyncMailRunner);
    }

    @Test
    @DisplayName("resetPassword: thành công => mã hoá mật khẩu, update DB, thu hồi mọi phiên, xoá lock login, dọn Redis và gửi mail")
    void resetPassword_success_executesAllCleanupStepsInOrder() throws Exception {
        String rawToken = "valid-token";
        String tokenHash = sha256(rawToken);
        String newPass = "MatKhauMoiDuDai!1";
        User user = User.builder().id("u-1").email(EMAIL).role(Role.USER).build();

        when(valueOps.get(TOKEN_KEY)).thenReturn(tokenHash);
        when(rateLimiter.compareAndDelete(TOKEN_KEY, tokenHash)).thenReturn(true);
        when(authRepository.findUserByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(passwordEncoder.encode(newPass)).thenReturn("$2a$12$newHash");

        service.resetPassword(EMAIL, rawToken, newPass);

        assertThat(user.getPassword()).isEqualTo("$2a$12$newHash");
        InOrder order = inOrder(authRepository, refreshTokenService, loginAttemptService, redis, asyncMailRunner);
        order.verify(authRepository).updateUserPassword(user);
        order.verify(refreshTokenService).revokeAll(EMAIL);
        order.verify(loginAttemptService).loginSucceeded(EMAIL);
        order.verify(redis).delete(List.of(OTP_KEY, OTP_FAIL_KEY, RESEND_KEY));

        ArgumentCaptor<Runnable> mailTask = ArgumentCaptor.forClass(Runnable.class);
        order.verify(asyncMailRunner).run(mailTask.capture());
        mailTask.getValue().run();
        verify(emailService).sendPasswordChangedNotice(EMAIL);
    }
}
