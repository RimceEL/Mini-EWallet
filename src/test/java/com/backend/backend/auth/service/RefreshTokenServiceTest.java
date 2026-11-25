package com.backend.backend.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import org.apache.commons.codec.digest.DigestUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.util.ReflectionTestUtils;

import com.backend.backend.auth.customexception.authentication.UnloginException;
import com.backend.backend.auth.customexception.token.ExpiredRefreshTokenException;

@ExtendWith(MockitoExtension.class)
public class RefreshTokenServiceTest {

    private static final String EMAIL = "an@gmail.com";
    private static final String JTI = "jti-1";
    private static final String RAW_TOKEN = "raw.refresh.token";
    private static final String HASH = DigestUtils.sha256Hex(RAW_TOKEN);
    private static final String KEY = "refresh_token:" + EMAIL + ":" + JTI;

    @Mock
    private StringRedisTemplate redis;
    @Mock
    private ValueOperations<String, String> valueOps;
    @Mock
    private JwtService jwtService;

    @InjectMocks
    private RefreshTokenService service;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "duration", 900_000L);
        lenient().when(redis.opsForValue()).thenReturn(valueOps);
    }

    @Test
    @DisplayName("store: chỉ lưu SHA-256 hex kèm TTL đúng cấu hình")
    void store_savesSha256WithDuration() {
        service.store(EMAIL, JTI, RAW_TOKEN);

        verify(valueOps).set(KEY, HASH, Duration.ofMillis(900_000L));
    }

    @Test
    @DisplayName("isValid: token hết hạn => ExpiredRefreshTokenException; không có trong Redis => UnloginException; sai/đúng hash => false/true")
    void isValid_coversExpiredMissingMismatchAndMatch() {
        when(valueOps.get(KEY)).thenReturn(HASH);
        when(jwtService.isExpired(RAW_TOKEN)).thenReturn(true);
        assertThatThrownBy(() -> service.isValid(EMAIL, JTI, RAW_TOKEN))
                .isInstanceOf(ExpiredRefreshTokenException.class);

        when(jwtService.isExpired(RAW_TOKEN)).thenReturn(false);
        when(valueOps.get(KEY)).thenReturn(null);
        assertThatThrownBy(() -> service.isValid(EMAIL, JTI, RAW_TOKEN))
                .isInstanceOf(UnloginException.class);

        when(valueOps.get(KEY)).thenReturn("other-hash");
        assertThat(service.isValid(EMAIL, JTI, RAW_TOKEN)).isFalse();

        when(valueOps.get(KEY)).thenReturn(HASH);
        assertThat(service.isValid(EMAIL, JTI, RAW_TOKEN)).isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("validateAndRevoke: expired => ném ExpiredRefreshTokenException không chạm Redis; -1/null => UnloginException; 0 => false; 1 => true")
    void validateAndRevoke_coversAllLuaOutcomes() {
        when(jwtService.isExpired(RAW_TOKEN)).thenReturn(true);
        assertThatThrownBy(() -> service.validateAndRevoke(EMAIL, JTI, RAW_TOKEN))
                .isInstanceOf(ExpiredRefreshTokenException.class);
        verify(redis, never()).execute(any(RedisScript.class), any(), any());

        when(jwtService.isExpired(RAW_TOKEN)).thenReturn(false);
        when(redis.execute(any(RedisScript.class), eq(List.of(KEY)), eq(HASH)))
                .thenReturn(-1L, null, 0L, 1L);

        assertThatThrownBy(() -> service.validateAndRevoke(EMAIL, JTI, RAW_TOKEN))
                .isInstanceOf(UnloginException.class);
        assertThatThrownBy(() -> service.validateAndRevoke(EMAIL, JTI, RAW_TOKEN))
                .isInstanceOf(UnloginException.class);
        assertThat(service.validateAndRevoke(EMAIL, JTI, RAW_TOKEN)).isFalse();
        assertThat(service.validateAndRevoke(EMAIL, JTI, RAW_TOKEN)).isTrue();
    }

    @Test
    @DisplayName("revoke & isExist: delegate đúng key")
    void revokeAndIsExist() {
        when(redis.hasKey(KEY)).thenReturn(true);
        when(redis.delete(KEY)).thenReturn(true);

        assertThat(service.isExist(EMAIL, JTI)).isTrue();
        assertThat(service.revoke(EMAIL, JTI)).isTrue();
    }

    @Test
    @DisplayName("revokeAll & clearUsed: khi keys null hoặc rỗng => trả 0L và không gọi delete")
    void revokeAllAndClearUsed_whenNoKeys_returnZeroWithoutDeleting() {
        when(redis.keys(anyString())).thenReturn(null, Collections.emptySet());

        assertThat(service.revokeAll(EMAIL)).isZero();
        assertThat(service.clearUsed(EMAIL)).isZero();

        verify(redis, never()).delete(ArgumentMatchers.<Collection<String>>any());
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("replaceSession: escape toàn bộ ký tự glob (\\ * ? [ ]) trong pattern truyền vào Lua")
    void replaceSession_escapesGlobWildcards() {
        String trickyEmail = "a*b?c[d]\\e@test.com";

        service.replaceSession(trickyEmail, JTI, RAW_TOKEN);

        verify(redis).execute(
                any(RedisScript.class),
                eq(List.of("refresh_token:" + trickyEmail + ":" + JTI)),
                eq("refresh_token:a\\*b\\?c\\[d\\]\\\\e@test.com:*"),
                eq(HASH),
                eq("900000"));
    }
}
