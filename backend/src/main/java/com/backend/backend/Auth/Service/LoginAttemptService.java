package com.backend.backend.Auth.Service;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class LoginAttemptService {

    private static final String FAIL_PREFIX = "login:fail:";
    private static final String LOCK_PREFIX = "login:lock:";

    private final StringRedisTemplate stringRedisTemplate;

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

    /** Gọi TRƯỚC khi xác thực. */
    public boolean isBlocked(String identifier) {
        return Boolean.TRUE.equals(stringRedisTemplate.hasKey(lockKey(identifier)));
    }

    /** Số giây còn lại của lần khóa hiện tại (0 nếu không bị khóa). */
    public long getRemainingLockSeconds(String identifier) {
        Long ttl = stringRedisTemplate.getExpire(lockKey(identifier), TimeUnit.SECONDS);
        return (ttl == null || ttl < 0) ? 0 : ttl;
    }

    /** Gọi khi đăng nhập SAI (BadCredentials). */
    public void loginFailed(String identifier) {
        String failKey = failKey(identifier);

        // INCR là atomic, an toàn khi nhiều request đồng thời
        Long attempts = stringRedisTemplate.opsForValue().increment(failKey);
        if (attempts == null) {
            return;
        }
        // Làm mới TTL của bộ đếm: quên hết sau khoảng dài nhất mà không sai thêm lần
        // nào
        stringRedisTemplate.expire(failKey, Duration.ofMinutes(counterTtlMinutes()));

        int lockMinutes = resolveLockMinutes(attempts);
        if (lockMinutes > 0) {
            stringRedisTemplate.opsForValue()
                    .set(lockKey(identifier), String.valueOf(attempts), Duration.ofMinutes(lockMinutes));
        }
    }

    /** Gọi khi đăng nhập THÀNH CÔNG. */
    public void loginSucceeded(String identifier) {
        stringRedisTemplate.delete(List.of(failKey(identifier), lockKey(identifier)));
    }

    // ---------- private ----------

    /**
     * Chạm ngưỡng cấp 1 hoặc 2 thì khóa theo cấp đó.
     * Từ ngưỡng cấp 3 trở đi, mỗi lần sai đều khóa cấp 3.
     * Trả về 0 nếu chưa cần khóa.
     */
    private int resolveLockMinutes(long attempts) {
        if (attempts >= loginAttemptLv3)
            return loginLockLv3;
        if (attempts >= loginAttemptLv2)
            return loginLockLv2;
        if (attempts >= loginAttemptLv1)
            return loginLockLv1;
        return 0;
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
