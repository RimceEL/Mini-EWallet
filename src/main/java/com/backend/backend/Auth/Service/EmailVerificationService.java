package com.backend.backend.auth.service;

import java.security.SecureRandom;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.backend.backend.auth.customexception.email.ExpiredVerificationCodeException;
import com.backend.backend.auth.customexception.email.InvalidVerificationCodeException;
import com.backend.backend.auth.customexception.email.TooManyResendAttemptsException;
import com.backend.backend.auth.customexception.email.TooManyVerificationAttemptsException;
import com.backend.backend.custom.RateLimiter;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class EmailVerificationService {

    private final StringRedisTemplate stringRedisTemplate;
    private final EmailService emailService;
    private static final SecureRandom secureRandom = new SecureRandom();
    private final RateLimiter rateLimiter;
    private final AsyncMailRunner asyncMailRunner;

    @Value("${app.verification.expiration-minutes:10}")
    private long expirationMinutes;

    @Value("${app.verification.max-attempts:5}")
    private int maxAttempts;

    @Value("${app.verification.code.resend.expiration-minutes:60}")
    private long expirationCodeResendMinutes;

    @Value("${app.verification.code.resend.max-attempts:5}")
    private int maxResendAttemps;

    private String codeKey(String email) {
        return "email_verify:code:" + email;
    }

    private String attemptsKey(String email) {
        return "email_verify:attempts:" + email;
    }

    private String resendAttemptsKey(String email) {
        return "email_verify:resend:attempts:" + email;
    }

    private String refundAttemptsKey(String email) {
        return "email_verify:refund:attempts:" + email;
    }

    private void enforceResendRateLimit(String email) {
        String resendAttemptsKey = resendAttemptsKey(email);
        Long attempts = rateLimiter.hit(resendAttemptsKey, expirationCodeResendMinutes * 60L, maxResendAttemps);
        if (attempts > maxResendAttemps) {
            throw new TooManyResendAttemptsException(
                    "Bạn đã gửi yêu cầu gửi mã xác thực quá nhiều lần, vui lòng thử lại sau "
                            + (expirationCodeResendMinutes / 60) + " giờ nữa");
        }
    }

    public void generateAndSend(String email) {
        enforceResendRateLimit(email);
        String code = String.format("%06d", secureRandom.nextInt(1_000_000));
        rateLimiter.setExAndDelete(codeKey(email), code, Duration.ofMinutes(expirationMinutes), attemptsKey(email));
        asyncMailRunner.run(() -> emailService.sendVerificationCode(email, code),
                e -> {
                    String refundKey = refundAttemptsKey(email);
                    if (rateLimiter.hit(refundKey, 24 * 3600L, 3) <= 3) { // tối đa 3 lần hoàn mỗi 24 giờ
                        rateLimiter.refund(resendAttemptsKey(email));
                    }
                });

    }

    public void verify(String email, String inputCode) {
        String attemptsKey = attemptsKey(email);
        Long attempts = rateLimiter.hit(attemptsKey, expirationMinutes * 60L, 1L);
        if (attempts != null && attempts > maxAttempts) {
            throw new TooManyVerificationAttemptsException(
                    "Bạn đã nhập sai quá số lần cho phép, vui lòng yêu cầu gửi lại mã");
        }
        String storedCode = stringRedisTemplate.opsForValue().get(codeKey(email));
        if (storedCode == null) {
            throw new ExpiredVerificationCodeException(
                    "Mã xác thực đã hết hạn, không tồn tại hoặc email không hợp lệ, vui lòng yêu cầu gửi lại mã");
        }
        if (!storedCode.equals(inputCode)) {
            throw new InvalidVerificationCodeException("Mã xác thực không đúng");
        }

        stringRedisTemplate.delete(codeKey(email));
        stringRedisTemplate.delete(attemptsKey);
    }
}
