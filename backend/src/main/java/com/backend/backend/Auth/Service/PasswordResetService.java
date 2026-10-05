package com.backend.backend.Auth.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.backend.backend.Auth.CustomException.Authentication.InvalidRegisterRequest;
import com.backend.backend.Auth.CustomException.Email.TooManyResendAttemptsException;
import com.backend.backend.Auth.CustomException.Otp.InvalidOtpException;
import com.backend.backend.Auth.CustomException.Otp.InvalidResetTokenException;
import com.backend.backend.Auth.CustomException.Otp.ManyOtpException;
import com.backend.backend.Auth.Model.User;
import com.backend.backend.Auth.Repository.AuthRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class PasswordResetService {

    private static final String OTP_PREFIX = "pwreset:otp:";
    private static final String OTP_FAIL_PREFIX = "pwreset:otp-fail:";
    private static final String TOKEN_PREFIX = "pwreset:token:";
    private static final String RESEND_PREFIX = "pwreset:resend:";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final StringRedisTemplate redis;
    private final AuthRepository authRepository;
    private final PasswordEncoder passwordEncoder;
    private final OtpService otpService;
    private final EmailService emailService;
    private final RefreshTokenService refreshTokenService;
    private final LoginAttemptService loginAttemptService;

    @Value("${app.password-reset.resend.max-attempts:5}")
    private int maxResendAttempts;
    @Value("${app.password-reset.resend.lock-hours:24}")
    private long resendLockHours;
    @Value("${app.password-reset.otp-ttl-minutes:10}")
    private int otpTtlMinutes;
    @Value("${app.password-reset.token-ttl-minutes:10}")
    private int tokenTtlMinutes;
    @Value("${app.password-reset.max-otp-attempts:5}")
    private int maxOtpAttempts;

    /**
     * Bước 1: xin OTP. Luôn trả về bình thường dù email có tồn tại hay không.
     */
    public void requestReset(String email) {
        enforceResendLimit(email);

        if (authRepository.findUserByEmail(email).isEmpty()) {
            return;
        }

        String otp = otpService.generate();

        redis.opsForValue().set(OTP_PREFIX + email, otpService.hash(otp), Duration.ofMinutes(otpTtlMinutes));
        redis.delete(OTP_FAIL_PREFIX + email);

        emailService.sendPasswordResetOtp(email, otp, otpTtlMinutes);
    }

    /**
     * Bước 2: đổi OTP lấy reset token (dùng một lần).
     */
    private static final String OTP_INVALID_MSG = "Otp đã hết hạn hoặc không hợp lệ";

    public String verifyOtp(String email, String otp) {
        String otpKey = OTP_PREFIX + email;
        String failKey = OTP_FAIL_PREFIX + email;

        String storedHash = redis.opsForValue().get(otpKey);
        if (storedHash == null) {
            throw new InvalidOtpException(OTP_INVALID_MSG);
        }

        // Đếm lần thử TRƯỚC khi so sánh → request song song cũng không vượt được giới
        // hạn
        Long fails = redis.opsForValue().increment(failKey);
        if (fails != null && fails == 1L) {
            redis.expire(failKey, Duration.ofMinutes(otpTtlMinutes));
        }
        if (fails != null && fails > maxOtpAttempts) {
            redis.delete(otpKey); // sai quá số lần → hủy OTP, phải xin mã mới
            throw new ManyOtpException("Bạn đã nhập sai OTP quá nhiều lần, vui lòng yêu cầu cấp mã mới");
        }

        if (!otpService.matches(otp, storedHash)) {
            throw new InvalidOtpException(OTP_INVALID_MSG);
        }

        // delete() chỉ trả true cho đúng 1 request → OTP chỉ đổi được 1 lần
        if (!Boolean.TRUE.equals(redis.delete(otpKey))) {
            throw new InvalidOtpException(OTP_INVALID_MSG);
        }
        redis.delete(failKey); // xóa bộ đếm sai sau khi thành công

        String token = generateToken();
        // key = prefix + email, value = hash của token (ghi đè token cũ nếu có)
        redis.opsForValue().set(TOKEN_PREFIX + email, sha256(token), Duration.ofMinutes(tokenTtlMinutes));
        return token;
    }

    /**
     * Bước 3: đặt mật khẩu mới bằng reset token.
     */
    private static final String TOKEN_INVALID_MSG = "Token đã hết hạn hoặc không hợp lệ";

    public void resetPassword(String email, String token, String newPassword) {
        if (token == null || token.isBlank()) {
            throw new InvalidResetTokenException("Phải gửi kèm token");
        }

        if (!ValidationUtils.isValidPassword(newPassword)) {
            throw new InvalidRegisterRequest("Mật khẩu không hợp lệ! Mật khẩu phải chứa từ 12 kí tự");
        }

        String tokenKey = TOKEN_PREFIX + email;
        String storedHash = redis.opsForValue().get(tokenKey);
        if (storedHash == null
                || !MessageDigest.isEqual(
                        storedHash.getBytes(StandardCharsets.UTF_8),
                        sha256(token).getBytes(StandardCharsets.UTF_8))) {
            throw new InvalidResetTokenException(TOKEN_INVALID_MSG);
        }

        // delete() chỉ trả true cho đúng 1 request → token chỉ dùng được 1 lần
        if (!Boolean.TRUE.equals(redis.delete(tokenKey))) {
            throw new InvalidResetTokenException(TOKEN_INVALID_MSG);
        }

        // phần còn lại giữ nguyên như code cũ
        User user = authRepository.findUserByEmail(email)
                .orElseThrow(InvalidResetTokenException::new);
        user.setPassword(passwordEncoder.encode(newPassword));
        authRepository.updateUserPassword(user);

        refreshTokenService.revokeAll(email);
        loginAttemptService.loginSucceeded(email);
        redis.delete(List.of(OTP_PREFIX + email, OTP_FAIL_PREFIX + email, RESEND_PREFIX + email));
        emailService.sendPasswordChangedNotice(email);
    }

    // ---------- private ----------

    private String generateToken() {
        byte[] bytes = new byte[32]; // 256 bit
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String sha256(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private void enforceResendLimit(String email) {
        String key = RESEND_PREFIX + email;

        Long attempts = redis.opsForValue().increment(key);
        if (attempts == null) {
            return;
        }
        if (attempts == 1L || attempts == maxResendAttempts) {
            redis.expire(key, Duration.ofHours(resendLockHours));
        }
        if (attempts > maxResendAttempts) {
            throw new TooManyResendAttemptsException(
                    "Bạn đã yêu cầu gửi mã quá nhiều lần, vui lòng thử lại sau " + resendLockHours + " giờ nữa");
        }
    }
}
