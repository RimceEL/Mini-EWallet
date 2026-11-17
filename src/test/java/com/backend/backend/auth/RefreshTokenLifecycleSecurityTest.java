package com.backend.backend.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Date;
import java.util.List;
import java.util.Set;

import org.apache.commons.codec.digest.DigestUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.MvcResult;

import com.backend.backend.auth.model.Role;
import com.jayway.jsonpath.JsonPath;

public class RefreshTokenLifecycleSecurityTest extends SecurityIntegrationTestBase {

    private static final String EMAIL = "an@gmail.com";

    @BeforeEach
    void seedUser() {
        createUser(EMAIL, PASSWORD, Role.USER, 1, 1);
    }

    private void assertAccessRejected(String accessToken, String expectedCode) throws Exception {
        MvcResult result = getWithToken(USER_URL, accessToken);
        assertThat(statusOf(result)).isEqualTo(401);
        assertThat(jsonString(result, "$.code")).isEqualTo(expectedCode);
    }

    @Test
    @DisplayName("Đăng nhập: Redis chỉ lưu BĂM SHA-256 của refresh token, không lưu token thô")
    void login_storesOnlyHashOfRefreshToken() throws Exception {
        Tokens tokens = loginOk(EMAIL, PASSWORD);

        Set<String> keys = sessionKeys(EMAIL);
        assertThat(keys).hasSize(1);
        String stored = redis.opsForValue().get(keys.iterator().next());
        assertThat(stored).isNotEqualTo(tokens.refresh());
        assertThat(stored).isEqualTo(DigestUtils.sha256Hex(tokens.refresh()));
        assertThat(redis.keys("*")).noneMatch(key -> key.contains(tokens.refresh()));
    }

    @Test
    @DisplayName("Refresh: cấp cặp token mới, token cũ bị thu hồi, access token cũ bị từ chối ngay")
    void refresh_rotatesTokens_andRevokesOldAccessToken() throws Exception {
        Tokens first = loginOk(EMAIL, PASSWORD);

        Tokens second = refreshOk(first.refresh());

        assertThat(second.refresh()).isNotEqualTo(first.refresh());
        assertThat(second.access()).isNotEqualTo(first.access());
        assertThat(sessionKeys(EMAIL)).hasSize(1);
        assertThat(statusOf(getWithToken(USER_URL, second.access()))).isEqualTo(200);
        assertAccessRejected(first.access(), "TOKEN_REVOKED");
    }

    @Test
    @DisplayName("Refresh nhiều lần liên tiếp không kéo dài vô hạn thời gian sống của phiên")
    void refresh_doesNotExtendSessionLifetime() throws Exception {
        Tokens first = loginOk(EMAIL, PASSWORD);
        long originalExpiry = jwtService.extractExpiration(first.refresh()).getTime();

        Tokens current = first;
        for (int i = 0; i < 3; i++) {
            current = refreshOk(current.refresh());
        }

        long latestExpiry = jwtService.extractExpiration(current.refresh()).getTime();
        assertThat(latestExpiry).isLessThanOrEqualTo(originalExpiry + 2000);
    }

    @Test
    @DisplayName("Dùng lại refresh token cũ => bị từ chối và HUỶ cả phiên mới (kẻ trộm và nạn nhân đều phải đăng nhập lại)")
    void reusedRefreshToken_revokesTheWholeSession() throws Exception {
        Tokens first = loginOk(EMAIL, PASSWORD);
        Tokens second = refreshOk(first.refresh());

        assertThat(statusOf(refreshRaw(first.refresh()))).isEqualTo(401);

        assertThat(sessionKeys(EMAIL)).isEmpty();
        assertThat(statusOf(refreshRaw(second.refresh()))).isEqualTo(401);
        assertAccessRejected(second.access(), "TOKEN_REVOKED");
    }

    @Test
    @DisplayName("Dùng lại token của ĐỜI ĐẦU (không phải đời ngay trước) vẫn bị phát hiện")
    void reusedOldestRefreshToken_isStillDetected() throws Exception {
        Tokens first = loginOk(EMAIL, PASSWORD);
        Tokens second = refreshOk(first.refresh());
        Tokens third = refreshOk(second.refresh());

        assertThat(statusOf(refreshRaw(first.refresh()))).isEqualTo(401);

        assertThat(sessionKeys(EMAIL)).isEmpty();
        assertThat(statusOf(refreshRaw(third.refresh()))).isEqualTo(401);
    }

    @Test
    @DisplayName("Token cũ (chưa từng xoay vòng) bị đăng nhập mới thay thế KHÔNG được phá phiên hiện tại")
    void staleTokenFromReplacedSession_cannotKillCurrentSession() throws Exception {
        Tokens stale = loginOk(EMAIL, PASSWORD);
        Tokens current = loginOk(EMAIL, PASSWORD);

        assertThat(statusOf(refreshRaw(stale.refresh()))).isEqualTo(401);

        assertThat(sessionKeys(EMAIL)).hasSize(1);
        assertThat(statusOf(getWithToken(USER_URL, current.access()))).isEqualTo(200);
        refreshOk(current.refresh());
    }

    @Test
    @DisplayName("Đăng xuất: thu hồi refresh token VÀ access token ngay lập tức")
    void logout_revokesBothTokens() throws Exception {
        Tokens tokens = loginOk(EMAIL, PASSWORD);

        assertThat(statusOf(logoutRaw(tokens.refresh()))).isEqualTo(200);

        assertThat(sessionKeys(EMAIL)).isEmpty();
        assertThat(statusOf(refreshRaw(tokens.refresh()))).isEqualTo(401);
        assertAccessRejected(tokens.access(), "TOKEN_REVOKED");
    }

    @Test
    @DisplayName("Đăng xuất hai lần bằng cùng token => lần hai bị từ chối")
    void logoutTwice_secondIsRejected() throws Exception {
        Tokens tokens = loginOk(EMAIL, PASSWORD);

        assertThat(statusOf(logoutRaw(tokens.refresh()))).isEqualTo(200);
        assertThat(statusOf(logoutRaw(tokens.refresh()))).isEqualTo(401);
    }

    @Test
    @DisplayName("Đăng xuất bằng ACCESS token (sai loại) => bị từ chối và phiên thật không bị ảnh hưởng")
    void logoutWithAccessToken_isRejected_andSessionSurvives() throws Exception {
        Tokens tokens = loginOk(EMAIL, PASSWORD);

        assertThat(statusOf(logoutRaw(tokens.access()))).isEqualTo(401);

        assertThat(sessionKeys(EMAIL)).hasSize(1);
        assertThat(statusOf(getWithToken(USER_URL, tokens.access()))).isEqualTo(200);
    }

    @Test
    @DisplayName("Dùng ACCESS token làm refresh token => 401, không tác dụng phụ")
    void accessTokenUsedAsRefresh_isRejected_withoutSideEffects() throws Exception {
        Tokens tokens = loginOk(EMAIL, PASSWORD);

        assertThat(statusOf(refreshRaw(tokens.access()))).isEqualTo(401);

        assertThat(sessionKeys(EMAIL)).hasSize(1);
        refreshOk(tokens.refresh());
    }

    @Test
    @DisplayName("Refresh token bị sửa 'sub' sang email khác (giữ chữ ký cũ) => 401, phiên thật còn nguyên")
    void tamperedRefreshToken_isRejected() throws Exception {
        Tokens tokens = loginOk(EMAIL, PASSWORD);
        String tampered = tamperPayload(tokens.refresh(), EMAIL, "admin@gmail.com");

        assertThat(statusOf(refreshRaw(tampered))).isEqualTo(401);

        assertThat(sessionKeys(EMAIL)).hasSize(1);
        refreshOk(tokens.refresh());
    }

    @Test
    @DisplayName("Refresh token ký bằng khoá khác (kể cả đúng email và jti thật) => 401")
    void refreshTokenSignedWithWrongKey_isRejected() throws Exception {
        Tokens tokens = loginOk(EMAIL, PASSWORD);
        String realJti = jwtService.extractJti(tokens.refresh());
        String forged = forgeToken("another-secret-another-secret-another-123", EMAIL, claims(realJti),
                new Date(System.currentTimeMillis() + 60_000));

        assertThat(statusOf(refreshRaw(forged))).isEqualTo(401);
        assertThat(sessionKeys(EMAIL)).hasSize(1);
    }

    @Test
    @DisplayName("Refresh token đã hết hạn (dù Redis vẫn còn phiên) => 401")
    void expiredRefreshToken_isRejected() throws Exception {
        Tokens tokens = loginOk(EMAIL, PASSWORD);
        String realJti = jwtService.extractJti(tokens.refresh());
        String expired = forgeToken(JWT_SECRET, EMAIL, claims(realJti), new Date(System.currentTimeMillis() - 60_000));

        assertThat(statusOf(refreshRaw(expired))).isEqualTo(401);
        assertThat(sessionKeys(EMAIL)).hasSize(1);
    }

    @ParameterizedTest(name = "refreshToken = [{0}] => không bao giờ là 2xx hoặc 5xx")
    @ValueSource(strings = { "", "   ", "khong-phai-jwt", "a.b.c", "eyJhbGciOiJIUzI1NiJ9.e30." })
    @DisplayName("Refresh token rác hoặc rỗng => 401, không bao giờ 500")
    void garbageRefreshToken_isRejectedCleanly(String garbage) throws Exception {
        MvcResult result = refreshRaw(garbage);

        assertThat(statusOf(result)).isEqualTo(401);
        assertThat(body(result)).doesNotContain("Exception").doesNotContain("at com.");
    }

    @ParameterizedTest(name = "body = {0}")
    @ValueSource(strings = { "{}", "{\"refreshToken\":null}", "[]", "\"x\"", "", "{\"refreshToken\":{\"a\":1}}" })
    @DisplayName("Body sai cấu trúc hoặc thiếu refreshToken => lỗi 4xx, không bao giờ 500")
    void malformedRefreshBody_neverCauses500(String json) throws Exception {
        MvcResult result = postJson("/api/v1/auth/refresh-token", json);

        assertThat(statusOf(result)).isBetween(400, 499);
        assertThat(body(result)).doesNotContain("accessToken");
    }

    @Test
    @DisplayName("Phiên hết hạn trong Redis (TTL) => refresh bị từ chối VÀ access token cũng bị từ chối")
    void sessionExpiringInRedis_killsBothTokens() throws Exception {
        Tokens tokens = loginOk(EMAIL, PASSWORD);
        String key = sessionKeys(EMAIL).iterator().next();

        redis.expire(key, Duration.ofMillis(300));
        Thread.sleep(900);

        assertThat(statusOf(refreshRaw(tokens.refresh()))).isEqualTo(401);
        assertAccessRejected(tokens.access(), "TOKEN_REVOKED");
    }

    @Test
    @DisplayName("User đã bị xoá khỏi DB => refresh không cấp token mới")
    void refresh_forDeletedUser_issuesNothing() throws Exception {
        Tokens tokens = loginOk(EMAIL, PASSWORD);
        jdbc.update("DELETE FROM users WHERE email = ?", EMAIL);

        MvcResult result = refreshRaw(tokens.refresh());

        assertThat(statusOf(result)).isBetween(400, 499);
        assertThat(body(result)).doesNotContain("accessToken");
    }

    @Test
    @DisplayName("[GIẢ THUYẾT] User bị KHOÁ sau khi đăng nhập => refresh KHÔNG được cấp token mới")
    void refresh_forBannedUser_shouldIssueNothing() throws Exception {
        Tokens tokens = loginOk(EMAIL, PASSWORD);
        setActive(EMAIL, 0);

        MvcResult result = refreshRaw(tokens.refresh());

        assertThat(statusOf(result)).as("tài khoản bị khoá vẫn nhận được token mới: " + body(result))
                .isBetween(400, 499);
        assertThat(body(result)).doesNotContain("accessToken");
    }

    @RepeatedTest(5)
    @DisplayName("8 request refresh đồng thời cùng một token => đúng 1 thắng, phiên không nhân bản, trạng thái nhất quán")
    void concurrentRefresh_sameToken_exactlyOneWinner_andStateIsCoherent() throws Exception {
        Tokens first = loginOk(EMAIL, PASSWORD);

        List<MvcResult> results = runConcurrently(8, () -> refreshRaw(first.refresh()));

        List<MvcResult> winners = results.stream().filter(r -> statusOf(r) == 200).toList();
        assertThat(winners).hasSize(1);
        assertThat(results).filteredOn(r -> statusOf(r) != 200).allMatch(r -> statusOf(r) == 401);
        assertThat(sessionKeys(EMAIL).size()).isLessThanOrEqualTo(1);

        Tokens winner = parseTokens(winners.get(0));
        boolean sessionAlive = !sessionKeys(EMAIL).isEmpty();
        assertThat(statusOf(getWithToken(USER_URL, winner.access())) == 200).isEqualTo(sessionAlive);
        assertThat(statusOf(refreshRaw(winner.refresh())) == 200).isEqualTo(sessionAlive);
    }

    @RepeatedTest(10)
    @DisplayName("[GIẢ THUYẾT] Đăng nhập đồng thời (tối đa LOCK_ATTEMPTS_1 luồng) cùng một user => chỉ còn đúng 1 phiên")
    void concurrentLogins_leaveExactlyOneSession() throws Exception {
        List<MvcResult> results = runConcurrently(LOCK_ATTEMPTS_1, () -> loginRaw(EMAIL, PASSWORD));

        assertThat(results).allMatch(r -> statusOf(r) == 200);
        assertThat(sessionKeys(EMAIL)).hasSize(1);
    }

    @Test
    @DisplayName("Đăng nhập lại thay thế phiên cũ: access và refresh của phiên cũ đều bị từ chối")
    void secondLogin_invalidatesFirstSession() throws Exception {
        Tokens first = loginOk(EMAIL, PASSWORD);
        Tokens second = loginOk(EMAIL, PASSWORD);

        assertAccessRejected(first.access(), "TOKEN_REVOKED");
        assertThat(statusOf(refreshRaw(first.refresh()))).isEqualTo(401);
        assertThat(statusOf(getWithToken(USER_URL, second.access()))).isEqualTo(200);
    }

    @Test
    @DisplayName("Huỷ phiên của user A (phát hiện dùng lại) không ảnh hưởng user B, kể cả email có tiền tố giống nhau")
    void revokingOneUser_doesNotAffectOthers() throws Exception {
        createUser("an2@gmail.com", PASSWORD, Role.USER, 1, 1);
        createUser("n@gmail.com", PASSWORD, Role.USER, 1, 1);
        Tokens victimNeighbour = loginOk("an2@gmail.com", PASSWORD);
        Tokens otherNeighbour = loginOk("n@gmail.com", PASSWORD);
        Tokens a0 = loginOk(EMAIL, PASSWORD);
        refreshOk(a0.refresh());

        assertThat(statusOf(refreshRaw(a0.refresh()))).isEqualTo(401);

        assertThat(statusOf(getWithToken(USER_URL, victimNeighbour.access()))).isEqualTo(200);
        assertThat(statusOf(getWithToken(USER_URL, otherNeighbour.access()))).isEqualTo(200);
    }

    @ParameterizedTest(name = "email kẻ tấn công = {0}")
    @ValueSource(strings = { "*@shared.test", "alic?@shared.test", "a*e@shared.test" })
    @DisplayName("[GIẢ THUYẾT] Email chứa ký tự đại diện (* ?) không được phép thu hồi phiên của người khác")
    void emailWithGlobCharacters_cannotRevokeOtherUsersSessions(String attackerEmail) throws Exception {
        String victimEmail = "alice@shared.test";
        createUser(victimEmail, PASSWORD, Role.USER, 1, 1);
        createUser(attackerEmail, PASSWORD, Role.USER, 1, 1);
        Tokens victim = loginOk(victimEmail, PASSWORD);

        loginOk(attackerEmail, PASSWORD);

        assertThat(sessionKeys(victimEmail)).as("phiên của nạn nhân bị xoá bởi người khác").hasSize(1);
        assertThat(statusOf(getWithToken(USER_URL, victim.access()))).isEqualTo(200);
    }

    @Test
    @DisplayName("Được nâng quyền USER -> ADMIN trong DB => CÙNG access token vào được API admin ngay")
    void promotionInDb_takesEffectOnNextRequest() throws Exception {
        Tokens tokens = loginOk(EMAIL, PASSWORD);
        assertThat(statusOf(getWithToken(ADMIN_URL, tokens.access()))).isEqualTo(403);

        setRole(EMAIL, Role.ADMIN);

        assertThat(statusOf(getWithToken(ADMIN_URL, tokens.access()))).isEqualTo(200);
        assertThat(jsonString(getWithToken(USER_URL, tokens.access()), "$.data.role")).isEqualTo("ADMIN");
    }

    @Test
    @DisplayName("Bị hạ quyền ADMIN -> USER trong DB => CÙNG access token bị chặn khỏi API admin ngay")
    void demotionInDb_takesEffectOnNextRequest() throws Exception {
        setRole(EMAIL, Role.ADMIN);
        Tokens tokens = loginOk(EMAIL, PASSWORD);
        assertThat(statusOf(getWithToken(ADMIN_URL, tokens.access()))).isEqualTo(200);
        assertThat(JsonPath.<List<Object>>read(payloadOf(tokens.access()), "$.roles").toString())
                .contains("ROLE_ADMIN");

        setRole(EMAIL, Role.USER);

        assertThat(statusOf(getWithToken(ADMIN_URL, tokens.access()))).isEqualTo(403);
    }

    @RepeatedTest(3)
    @DisplayName("Đổi role liên tục 20 lần: mỗi request đều phản ánh đúng trạng thái DB mới nhất (không cache)")
    void roleFlipFlop_everyRequestSeesLatestDbState() throws Exception {
        Tokens tokens = loginOk(EMAIL, PASSWORD);

        for (int i = 0; i < 20; i++) {
            boolean admin = i % 2 == 0;
            setRole(EMAIL, admin ? Role.ADMIN : Role.USER);
            assertThat(statusOf(getWithToken(ADMIN_URL, tokens.access())))
                    .as("lần thứ " + i + " với role " + (admin ? "ADMIN" : "USER"))
                    .isEqualTo(admin ? 200 : 403);
        }
    }

    @Test
    @DisplayName("Bị khoá trong DB => access token bị chặn ngay (403 USER_IS_BANNED), mở khoá thì dùng lại được")
    void banInDb_takesEffectImmediately_andUnbanRestoresAccess() throws Exception {
        Tokens tokens = loginOk(EMAIL, PASSWORD);

        setActive(EMAIL, 0);
        MvcResult banned = getWithToken(USER_URL, tokens.access());
        assertThat(statusOf(banned)).isEqualTo(403);
        assertThat(jsonString(banned, "$.code")).isEqualTo("USER_IS_BANNED");

        setActive(EMAIL, 1);
        assertThat(statusOf(getWithToken(USER_URL, tokens.access()))).isEqualTo(200);
    }

    @Test
    @DisplayName("Bị đánh dấu chưa xác thực email trong DB => access token bị chặn ngay (401 USER_IS_NOT_VERIFIED)")
    void unverifiedInDb_takesEffectImmediately() throws Exception {
        Tokens tokens = loginOk(EMAIL, PASSWORD);

        setVerified(EMAIL, 0);

        MvcResult result = getWithToken(USER_URL, tokens.access());
        assertThat(statusOf(result)).isEqualTo(401);
        assertThat(jsonString(result, "$.code")).isEqualTo("USER_IS_NOT_VERIFIED");
    }

    @Test
    @DisplayName("User bị xoá khỏi DB => access token bị chặn ngay (401 TOKEN_INVALID)")
    void userDeletedInDb_takesEffectImmediately() throws Exception {
        Tokens tokens = loginOk(EMAIL, PASSWORD);

        jdbc.update("DELETE FROM users WHERE email = ?", EMAIL);

        MvcResult result = getWithToken(USER_URL, tokens.access());
        assertThat(statusOf(result)).isEqualTo(401);
        assertThat(jsonString(result, "$.code")).isEqualTo("TOKEN_INVALID");
    }
}