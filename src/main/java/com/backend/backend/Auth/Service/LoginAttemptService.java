package com.backend.backend.Auth.Service;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.backend.backend.Custom.RateLimiter;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class LoginAttemptService {

    private static final String FAIL_PREFIX = "login:fail:";
    private static final String LOCK_PREFIX = "login:lock:";

    private final StringRedisTemplate stringRedisTemplate;
    private final RateLimiter rateLimiter;

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

    public boolean isBlocked(String identifier) {
        return Boolean.TRUE.equals(stringRedisTemplate.hasKey(lockKey(identifier)));
    }

    public long getRemainingLockSeconds(String identifier) {
        Long ttl = stringRedisTemplate.getExpire(lockKey(identifier), TimeUnit.SECONDS);
        return (ttl == null || ttl < 0) ? 0 : ttl;
    }

    public void loginFailed(String identifier) {
        String failKey = failKey(identifier);
        Long attempts = rateLimiter.incrementAndExpire(failKey, Duration.ofMinutes(counterTtlMinutes()));
        int lockMinutes = resolveLockMinutes(attempts);
        if (lockMinutes > 0) {
            stringRedisTemplate.opsForValue()
                    .set(lockKey(identifier), String.valueOf(attempts), Duration.ofMinutes(lockMinutes));
        }
    }

    public void loginSucceeded(String identifier) {
        stringRedisTemplate.delete(List.of(failKey(identifier), lockKey(identifier)));
    }

    private int resolveLockMinutes(long attempts) {
        if (attempts == loginAttemptLv3)
            return loginLockLv3;
        if (attempts == loginAttemptLv2)
            return loginLockLv2;
        if (attempts == loginAttemptLv1)
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
