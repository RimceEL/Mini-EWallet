package com.backend.backend.auth.service;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import com.backend.backend.auth.customexception.authentication.LoginLockedException;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class LoginAttemptService {

    private static final String FAIL_PREFIX = "login:fail:";
    private static final String LOCK_PREFIX = "login:lock:";

    private static final long STATUS_OK = 0; // chưa bị khoá
    private static final long STATUS_LOCK = 3;
    private static final long STATUS_LOCKED_BEFORE = 4;

    @SuppressWarnings("rawtypes")
    private static final DefaultRedisScript<List> LOGIN_FAILED = new DefaultRedisScript<>("""
            -- KEYS[1] = failKey, KEYS[2] = lockKey
            -- ARGV[1] = counterTtlSeconds
            -- ARGV[2],[3] = attempts lv1, lockSeconds lv1
            -- ARGV[4],[5] = attempts lv2, lockSeconds lv2
            -- ARGV[6],[7] = attempts lv3, lockSeconds lv3
            --
            -- Giá trị của lockKey có dạng "<level>:<n>", ví dụ "3:10"
            --
            -- Giá trị trả về {status, n}:
            --   0 = chưa bị khoá
            --   1 = vừa đặt khoá lv1 hoặc lv2
            --   2 = đang bị khoá lv1 hoặc lv2 từ trước (không tăng bộ đếm)
            --   3 = vừa đặt khoá lv3 (báo + gửi mail)
            --   4 = đang bị khoá lv3 từ trước (chỉ báo, không gửi mail)

            -- 1. Đang khoá thì không tăng, chỉ báo trạng thái
            --    Đọc level từ giá trị của lockKey để biết đang ở khoá lv3 hay không
            if redis.call('EXISTS', KEYS[2]) == 1 then
                local cur = tonumber(redis.call('GET', KEYS[1])) or 0
                local lockVal = redis.call('GET', KEYS[2]) or ''
                if string.sub(lockVal, 1, 1) == '3' then
                    return {4, cur}
                end
                return {2, cur}
            end

            -- 2. Tăng bộ đếm và đặt expire (refresh mỗi lần fail như cũ)
            local n = redis.call('INCR', KEYS[1])
            redis.call('EXPIRE', KEYS[1], ARGV[1])

            -- 3. Xác định mức khoá và thời gian khoá (giữ nguyên thứ tự lv3 -> lv2 -> lv1)
            local lockSeconds = 0
            local level = 0
            if n >= tonumber(ARGV[6]) + 1 then
                lockSeconds = tonumber(ARGV[7])
                level = 3
            elseif n == tonumber(ARGV[4]) + 1 then
                lockSeconds = tonumber(ARGV[5])
                level = 2
            elseif n == tonumber(ARGV[2]) + 1 then
                lockSeconds = tonumber(ARGV[3])
                level = 1
            end

            -- 4. Set khoá lock với TTL, giá trị "<level>:<n>"
            if lockSeconds > 0 then
                redis.call('SET', KEYS[2], level .. ':' .. tostring(n), 'EX', lockSeconds)
                if level == 3 then
                    return {3, n}
                end
                return {1, n}
            end
            return {0, n}
            """, List.class);

    private final StringRedisTemplate stringRedisTemplate;
    private final AsyncMailRunner asyncMailRunner;
    private final EmailService emailService;

    @Value("${app.login.max-attempts-1}")
    private int loginAttemptLv1;
    @Value("${app.login.max-attempts-2}")
    private int loginAttemptLv2;
    @Value("${app.login.max-attempts-3}")
    private int loginAttemptLv3;

    @Value("${app.login.expiration-minutes-1}")
    private int loginLockLv1;
    @Value("${app.login.expiration-minutes-2}")
    private int loginLockLv2;
    @Value("${app.login.expiration-minutes-3}")
    private int loginLockLv3;

    @Value("${app.login.attempt.duration-minutes}")
    private int attemptsDuration;

    @PostConstruct
    void validateLockConfig() {
        if (loginLockLv3 < attemptsDuration) {
            throw new IllegalStateException("app.login.expiration-minutes-3 (" + loginLockLv3
                    + ") phải >= app.login.attempt.duration-minutes (" + attemptsDuration + ")");
        }
    }

    public boolean isBlocked(String identifier) {
        return Boolean.TRUE.equals(stringRedisTemplate.hasKey(lockKey(identifier)));
    }

    public long getRemainingLockSeconds(String identifier) {
        Long ttl = stringRedisTemplate.getExpire(lockKey(identifier), TimeUnit.SECONDS);
        return (ttl == null || ttl < 0) ? 0 : ttl;
    }

    public boolean loginAttemptFailed(String identifier) {
        @SuppressWarnings("unchecked")
        List<Long> result = stringRedisTemplate.execute(
                LOGIN_FAILED,
                List.of(failKey(identifier), lockKey(identifier)),
                String.valueOf(Duration.ofMinutes(counterTtlMinutes()).toSeconds()),
                String.valueOf(loginAttemptLv1), String.valueOf(loginLockLv1 * 60L),
                String.valueOf(loginAttemptLv2), String.valueOf(loginLockLv2 * 60L),
                String.valueOf(loginAttemptLv3), String.valueOf(loginLockLv3 * 60L));
        if (result != null && !result.isEmpty()
                && (result.get(0) == STATUS_LOCK || result.get(0) == STATUS_LOCKED_BEFORE)) {
            if (result.get(0) == STATUS_LOCK)
                asyncMailRunner.run(() -> emailService.sendTryToLoginWarning(identifier));
            throw new LoginLockedException(
                    "Tài khoản của bạn đã tạm thời bị khoá do đăng nhập sai quá nhiều lần! Vui lòng đặt lại mật khẩu");
        }

        return result != null && !result.isEmpty() && result.get(0) != STATUS_OK;
    }

    public void loginSucceeded(String identifier) {
        stringRedisTemplate.delete(List.of(failKey(identifier), lockKey(identifier)));
    }

    private long counterTtlMinutes() {
        return attemptsDuration;
    }

    private String failKey(String identifier) {
        return FAIL_PREFIX + normalize(identifier);
    }

    private String lockKey(String identifier) {
        return LOCK_PREFIX + normalize(identifier);
    }

    private String normalize(String identifier) {
        return identifier == null ? "" : identifier.trim().toLowerCase();
    }
}
