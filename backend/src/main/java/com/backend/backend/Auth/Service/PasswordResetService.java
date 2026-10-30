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
import org.springframework.mail.MailException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.backend.backend.Auth.CustomException.Authentication.InvalidRegisterRequest;
import com.backend.backend.Auth.CustomException.Email.TooManyResendAttemptsException;
import com.backend.backend.Auth.CustomException.Otp.InvalidOtpException;
import com.backend.backend.Auth.CustomException.Otp.InvalidResetTokenException;
import com.backend.backend.Auth.CustomException.Otp.ManyOtpException;
import com.backend.backend.Auth.Model.User;
import com.backend.backend.Auth.Repository.AuthRepository;
import com.backend.backend.Custom.RateLimiter;
import com.backend.backend.Transaction.CustomException.MailSendFailedException;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class PasswordResetService {

    private static final String OTP_PREFIX = "pwreset:otp:";
    private static final String OTP_FAIL_PREFIX = "pwreset:otp-fail:";
    private static final String TOKEN_PREFIX = "pwreset:token:";
    private static final String RESEND_PREFIX = "pwreset:resend:";
    private static final String REFUND_PREFIX = "pwreset:refund:";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final StringRedisTemplate redis;
    private final AuthRepository authRepository;
    private final PasswordEncoder passwordEncoder;
    private final OtpService otpService;
    private final EmailService emailService;
    private final RefreshTokenService refreshTokenService;
    private final LoginAttemptService loginAttemptService;
    private final RateLimiter rateLimiter;

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
        String key = RESEND_PREFIX + email;
        Long attempts = rateLimiter.hit(key, resendLockHours * 3600L, maxResendAttempts);
        if (attempts > maxResendAttempts)
            throw new TooManyResendAttemptsException(
                    "Bạn đã yêu cầu gửi mã quá nhiều lần, vui lòng thử lại sau " + resendLockHours + " giờ nữa");
        if (authRepository.findUserByEmail(email).isEmpty())
            return;
        String otp = otpService.generate();
        rateLimiter.setExAndDelete(
                OTP_PREFIX + email,
                otpService.hash(otp),
                Duration.ofMinutes(otpTtlMinutes),
                OTP_FAIL_PREFIX + email);
        try {
            emailService.sendPasswordResetOtp(email, otp, otpTtlMinutes);
        } catch (MailException e) {
            String refundKey = REFUND_PREFIX + email;
            if (rateLimiter.hit(refundKey, 24 * 3600L, 3) <= 3) { // tối đa 3 lần hoàn mỗi 24 giờ
                rateLimiter.refund(key);
            }
            throw new MailSendFailedException( // Vẫn có thể ném exception dù đã gửi mail thành công
                    "Chưa xác nhận được việc gửi email. Nếu bạn đã nhận được mã thì cứ nhập, nếu chưa thì hãy yêu cầu gửi lại");
        }
    }

    /**
     * Bước 2: đổi OTP lấy reset token (dùng một lần).
     */
    private static final String OTP_INVALID_MSG = "Otp đã hết hạn hoặc không hợp lệ";

    public String verifyOtp(String email, String otp) {
        String otpKey = OTP_PREFIX + email;
        String failKey = OTP_FAIL_PREFIX + email;

        String storedHash = redis.opsForValue().get(otpKey);

        // Đếm lần thử TRƯỚC khi so sánh → request song song cũng không vượt được giới
        // hạn
        Long fails = rateLimiter.hit(failKey, otpTtlMinutes * 60L * 2L, maxOtpAttempts);
        if (fails != null && fails > maxOtpAttempts) {
            redis.delete(otpKey); // sai quá số lần → hủy OTP, phải xin mã mới
            throw new ManyOtpException("Bạn đã nhập sai OTP quá nhiều lần, vui lòng yêu cầu cấp mã mới");
        }

        // compareAndDelete chỉ thành công với đúng 1 request
        if (storedHash == null
                || !otpService.matches(otp, storedHash)
                || !rateLimiter.compareAndDelete(otpKey, storedHash)) // Kiểm tra otp thật sự trong redis thêm lần nữa
                                                                      // vì có thể otp thật đã bị ghi đè do một
                                                                      // request send otp
            throw new InvalidOtpException(OTP_INVALID_MSG);

        String token = generateToken();
        rateLimiter.setExAndDelete(
                TOKEN_PREFIX + email,
                sha256(token),
                Duration.ofMinutes(tokenTtlMinutes),
                failKey);
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

        // compareAndDelete chỉ thành công với đúng 1 request
        if (storedHash == null
                || !MessageDigest.isEqual(
                        storedHash.getBytes(StandardCharsets.UTF_8),
                        sha256(token).getBytes(StandardCharsets.UTF_8))
                || !rateLimiter.compareAndDelete(tokenKey, storedHash))
            throw new InvalidResetTokenException(TOKEN_INVALID_MSG);

        User user = authRepository.findUserByEmail(email)
                .orElseThrow(InvalidResetTokenException::new);
        user.setPassword(passwordEncoder.encode(newPassword));
        authRepository.updateUserPassword(user);

        refreshTokenService.revokeAll(email);
        loginAttemptService.loginSucceeded(email);
        redis.delete(List.of(OTP_PREFIX + email, OTP_FAIL_PREFIX + email, RESEND_PREFIX + email));
        emailService.sendPasswordChangedNotice(email);
    }

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
}