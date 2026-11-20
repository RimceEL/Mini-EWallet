package com.backend.backend.auth.service;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import com.backend.backend.auth.customexception.authentication.UnloginException;
import com.backend.backend.auth.customexception.token.ExpiredRefreshTokenException;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class RefreshTokenService {
    private final StringRedisTemplate stringRedisTemplate;
    private final JwtService jwtService;
    @Value("${jwt.refresh.expiration}")
    private long duration;

    public String key(String email, String jti) {
        return "refresh_token:" + email + ":" + jti;
    }

    public void store(String email, String jti, String rawToken) {
        String hashedToken = DigestUtils.sha256Hex(rawToken);
        stringRedisTemplate.opsForValue().set(key(email, jti), hashedToken, Duration.ofMillis(duration));
    }

    public boolean isExist(String email, String jti) {
        return stringRedisTemplate.hasKey(key(email, jti));
    }

    public boolean isValid(String email, String jti, String rawToken) {
        String stored = stringRedisTemplate.opsForValue().get(key(email, jti));
        if (jwtService.isExpired(rawToken))
            throw new ExpiredRefreshTokenException("");
        if (stored == null)
            throw new UnloginException("");
        return stored.equals(DigestUtils.sha256Hex(rawToken));
    }

    public boolean revoke(String email, String jti) {
        return stringRedisTemplate.delete(key(email, jti));
    }

    private static final DefaultRedisScript<Long> VALIDATE_AND_REVOKE = new DefaultRedisScript<>("""
            -- KEYS[1] = refresh_token:<email>:<jti>
            -- ARGV[1] = sha256(rawToken)
            local stored = redis.call('GET', KEYS[1])
            if not stored then
                return -1
            end
            if stored ~= ARGV[1] then
                return 0
            end
            redis.call('DEL', KEYS[1])
            return 1
            """, Long.class);

    public boolean validateAndRevoke(String email, String jti, String rawToken) {
        if (jwtService.isExpired(rawToken)) {
            throw new ExpiredRefreshTokenException("");
        }
        String hashedToken = DigestUtils.sha256Hex(rawToken);
        Long result = stringRedisTemplate.execute(
                VALIDATE_AND_REVOKE,
                List.of(key(email, jti)),
                hashedToken);
        if (result == null || result == -1L) {
            throw new UnloginException("");
        }
        return result == 1L;
    }

    public Long revokeAll(String email) {
        Set<String> keys = stringRedisTemplate.keys("refresh_token:" + escapeGlob(email) + ":*");
        if (keys == null || keys.isEmpty()) {
            return 0L;
        }
        return stringRedisTemplate.delete(keys);
    }

    public void replaceSession(String email, String jti, String rawToken) {
        stringRedisTemplate.execute(REPLACE_SESSION,
                List.of(key(email, jti)),
                "refresh_token:" + escapeGlob(email) + ":*",
                DigestUtils.sha256Hex(rawToken),
                String.valueOf(duration));
    }

    public String usedKey(String email, String rawToken) {
        return "used_refresh_token:" + email + ":" + DigestUtils.sha256Hex(rawToken);
    }

    // Gọi sau khi revoke() thành công: ghi nhớ token này đã được dùng
    public void markUsed(String email, String rawToken) {
        stringRedisTemplate.opsForValue().set(usedKey(email, rawToken), "1", Duration.ofMillis(duration));
    }

    public boolean isUsed(String email, String rawToken) {
        return Boolean.TRUE.equals(stringRedisTemplate.hasKey(usedKey(email, rawToken)));
    }

    // Dùng khi logout
    public Long clearUsed(String email) {
        Set<String> keys = stringRedisTemplate.keys("used_refresh_token:" + escapeGlob(email) + ":*");
        if (keys == null || keys.isEmpty()) {
            return 0L;
        }
        return stringRedisTemplate.delete(keys);
    }

    private static final DefaultRedisScript<Long> REPLACE_SESSION = new DefaultRedisScript<>("""
            -- KEYS[1] = key phiên mới
            -- ARGV[1] = mẫu key các phiên cũ, ARGV[2] = hash token, ARGV[3] = TTL (ms)
            local old = redis.call('KEYS', ARGV[1])
            for i = 1, #old do
                redis.call('DEL', old[i])
            end
            redis.call('SET', KEYS[1], ARGV[2], 'PX', ARGV[3])
            return #old
            """, Long.class);

    private static String escapeGlob(String s) {
        return s.replaceAll("([\\\\*?\\[\\]])", "\\\\$1");
        // Tránh các trường hợp đăng nhập sử dụng ký tự *, ?, [] khiến redis quét nhầm
        // và xoá phiên của người khác
    }
}
