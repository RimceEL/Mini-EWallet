package com.backend.backend.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.Map;

import javax.crypto.SecretKey;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.util.ReflectionTestUtils;

import com.backend.backend.auth.model.Role;
import com.backend.backend.auth.model.User;

import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

public class JwtServiceTest {

    private static final String SECRET = "jwt-unit-test-secret-0123456789-abcdef";
    private static final String OTHER_SECRET = "another-jwt-secret-0123456789-abcdef";

    private JwtService jwtService;

    private static User user(String email, Role role) {
        return User.builder()
                .id("u-1")
                .email(email)
                .username("nguoidung01")
                .password("hash")
                .role(role)
                .isActive(1)
                .isVerified(1)
                .build();
    }

    @BeforeEach
    void setUp() {
        jwtService = new JwtService();
        ReflectionTestUtils.setField(jwtService, "SERECT_KEY", SECRET);
        ReflectionTestUtils.setField(jwtService, "JWT_ACCESS_TOKEN_EXPIRATION", 60_000L);
        ReflectionTestUtils.setField(jwtService, "JWT_REFRESH_TOKEN_EXPIRATION", 600_000L);
        ReflectionTestUtils.setField(jwtService, "MIN_SECRET_BYTES", 32);
        jwtService.init();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = { "   ", "\t", "\n" })
    @DisplayName("init(): jwt.secret null/rỗng/toàn khoảng trắng => ném IllegalStateException")
    void init_blankOrNullSecret_throwsIllegalState(String badSecret) {
        JwtService svc = new JwtService();
        ReflectionTestUtils.setField(svc, "SERECT_KEY", badSecret);
        ReflectionTestUtils.setField(svc, "MIN_SECRET_BYTES", 32);

        assertThatThrownBy(svc::init)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("jwt.secret");
    }

    @Test
    @DisplayName("init(): đếm độ dài theo UTF-8 bytes (11 ký tự tiếng Việt = 33 bytes >= 32 => khởi động thành công)")
    void init_multibyteUtf8SecretAtLeast32Bytes_succeeds() {
        String utf8Secret = "ệ".repeat(11);
        assertThat(utf8Secret.getBytes(StandardCharsets.UTF_8).length).isEqualTo(33);

        new ApplicationContextRunner()
                .withUserConfiguration(JwtService.class)
                .withPropertyValues(
                        "jwt.secret=" + utf8Secret,
                        "jwt.access.expiration=60000",
                        "jwt.refresh.expiration=600000",
                        "jwt.min_secret_bytes=32")
                .run(ctx -> assertThat(ctx).hasNotFailed());
    }

    @Test
    @DisplayName("accessTokenGenerate: gắn đúng sub, jti, roles, iat và exp")
    void accessTokenGenerate_populatesAllClaimsAndDates() {
        long before = System.currentTimeMillis() - 1000;
        User u = user("an@gmail.com", Role.ADMIN);

        String token = jwtService.accessTokenGenerate(u, "jti-abc");

        Date iat = jwtService.extractIssued(token);
        Date exp = jwtService.extractExpiration(token);
        assertThat(jwtService.extractUsername(token)).isEqualTo("an@gmail.com");
        assertThat(jwtService.extractJti(token)).isEqualTo("jti-abc");
        assertThat(jwtService.extractRoles(token)).containsExactly("ROLE_ADMIN");
        assertThat(iat.getTime()).isBetween(before, System.currentTimeMillis() + 1000);
        assertThat(exp.getTime() - iat.getTime()).isBetween(58_000L, 62_000L);
        assertThat(jwtService.isExpired(token)).isFalse();
    }

    @Test
    @DisplayName("refreshTokenGenerate (mặc định & custom TTL): không có claim roles và giữ đúng thời hạn")
    void refreshTokenGenerate_hasNoRolesAndHonorsCustomExpiration() {
        User u = user("an@gmail.com", Role.USER);

        String defaultRefresh = jwtService.refreshTokenGenerate(u, "jti-1");
        String customRefresh = jwtService.refreshTokenGenerate(u, "jti-2", 120_000L);

        assertThat(jwtService.extractRoles(defaultRefresh)).isEmpty();
        assertThat(jwtService.extractRoles(customRefresh)).isEmpty();
        assertThat(jwtService.extractExpiration(customRefresh).getTime()
                - jwtService.extractIssued(customRefresh).getTime()).isBetween(118_000L, 122_000L);
    }

    @Test
    @DisplayName("extractClaimList: đọc đúng list claim khi có và trả list rỗng khi claim không tồn tại")
    void extractClaimList_presentAndMissingClaims() {
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        String token = Jwts.builder()
                .subject("an@gmail.com")
                .claims(Map.of("scopes", List.of("read", "write")))
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(key, Jwts.SIG.HS256)
                .compact();

        assertThat(jwtService.extractClaimList(token, "scopes")).containsExactly("read", "write");
        assertThat(jwtService.extractClaimList(token, "missing")).isEmpty();
    }

    @Test
    @DisplayName("isTokenValid: true với đúng user, false khi UserDetails khác email")
    void isTokenValid_matchesSubjectAgainstUserDetails() {
        User owner = user("an@gmail.com", Role.USER);
        User stranger = user("khac@gmail.com", Role.USER);
        String token = jwtService.accessTokenGenerate(owner, "jti-1");

        assertThat(jwtService.isTokenValid(token, owner)).isTrue();
        assertThat(jwtService.isTokenValid(token, stranger)).isFalse();
    }

    @Test
    @DisplayName("isExpired & parse: token hết hạn ném ExpiredJwtException, token sai secret/rác ném JwtException")
    void expiredAndInvalidTokens_throwJwtExceptions() {
        SecretKey sameKey = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        SecretKey otherKey = Keys.hmacShaKeyFor(OTHER_SECRET.getBytes(StandardCharsets.UTF_8));
        String expired = Jwts.builder()
                .subject("an@gmail.com")
                .expiration(new Date(System.currentTimeMillis() - 5_000))
                .signWith(sameKey, Jwts.SIG.HS256)
                .compact();
        String wrongKey = Jwts.builder()
                .subject("an@gmail.com")
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(otherKey, Jwts.SIG.HS256)
                .compact();

        assertThatThrownBy(() -> jwtService.isExpired(expired)).isInstanceOf(ExpiredJwtException.class);
        assertThatThrownBy(() -> jwtService.extractUsername(wrongKey)).isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> jwtService.extractUsername("not-a-jwt")).isInstanceOf(JwtException.class);
    }
}
