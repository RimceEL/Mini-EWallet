package com.backend.backend.Auth.Service;

import java.time.Duration;
import java.util.Set;

import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.backend.backend.Auth.CustomException.Authentication.UnloginException;
import com.backend.backend.Auth.CustomException.Token.ExpiredRefreshTokenException;

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

    // public boolean isExist(String email) {
    // Set<String> keys = stringRedisTemplate.keys("refresh_token:" + email + ":*");
    // return keys != null && !keys.isEmpty();
    // }

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

    public void revoke(String email, String jti) {
        stringRedisTemplate.delete(key(email, jti));
    }

    public void revokeAll(String email) {
        Set<String> keys = stringRedisTemplate.keys("refresh_token:" + email + ":*");
        if (keys != null && !keys.isEmpty()) {
            stringRedisTemplate.delete(keys);
        }
    }
}
