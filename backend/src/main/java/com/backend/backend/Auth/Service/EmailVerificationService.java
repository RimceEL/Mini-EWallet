package com.backend.backend.Auth.Service;

import java.security.SecureRandom;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.backend.backend.Auth.CustomException.ExpiredVerificationCodeException;
import com.backend.backend.Auth.CustomException.InvalidVerificationCodeException;
import com.backend.backend.Auth.CustomException.TooManyVerificationAttemptsException;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class EmailVerificationService {

    private final StringRedisTemplate stringRedisTemplate;
    private final EmailService emailService;
    private final SecureRandom secureRandom = new SecureRandom();

    @Value("${app.verification.expiration-minutes:10}")
    private long expirationMinutes;

    @Value("${app.verification.max-attempts:5}")
    private int maxAttempts;

    private String codeKey(String email) {
        return "email_verify:code:" + email;
    }

    private String attemptsKey(String email) {
        return "email_verify:attempts:" + email;
    }

    public void generateAndSend(String email) {
        String code = String.format("%06d", secureRandom.nextInt(1_000_000));
        stringRedisTemplate.opsForValue().set(codeKey(email), code, Duration.ofMinutes(expirationMinutes));
        stringRedisTemplate.delete(attemptsKey(email)); // reset số lần nhập sai mỗi khi cấp mã mới
        emailService.sendVerificationCode(email, code);
    }

    public void verify(String email, String inputCode) {
        String attemptsKey = attemptsKey(email);
        Long attempts = stringRedisTemplate.opsForValue().increment(attemptsKey);
        if (attempts != null && attempts == 1L) {
            stringRedisTemplate.expire(attemptsKey, Duration.ofMinutes(expirationMinutes));
        }
        if (attempts != null && attempts > maxAttempts) {
            throw new TooManyVerificationAttemptsException(
                    "Bạn đã nhập sai quá số lần cho phép, vui lòng yêu cầu gửi lại mã");
        }

        String storedCode = stringRedisTemplate.opsForValue().get(codeKey(email));
        if (storedCode == null) {
            throw new ExpiredVerificationCodeException(
                    "Mã xác thực đã hết hạn hoặc không tồn tại, vui lòng yêu cầu gửi lại mã");
        }
        if (!storedCode.equals(inputCode)) {
            throw new InvalidVerificationCodeException("Mã xác thực không đúng");
        }

        stringRedisTemplate.delete(codeKey(email));
        stringRedisTemplate.delete(attemptsKey);
    }
}
