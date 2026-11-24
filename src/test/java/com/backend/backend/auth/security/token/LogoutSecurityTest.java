package com.backend.backend.auth.security.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.Date;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import com.backend.backend.auth.model.Role;
import com.backend.backend.auth.security.SecurityIntegrationTestBase;

public class LogoutSecurityTest extends SecurityIntegrationTestBase {

    private static final String EMAIL = "an@gmail.com";
    private static final String LOGOUT_URL = "/api/v1/auth/logout";

    @BeforeEach
    void seedUser() {
        createUser(EMAIL, PASSWORD, Role.USER, 1, 1);
    }

    private void assertSessionSurvives(Tokens tokens) throws Exception {
        assertThat(sessionKeys(EMAIL)).hasSize(1);
        assertThat(statusOf(getWithToken(USER_URL, tokens.access()))).isEqualTo(200);
        refreshOk(tokens.refresh());
    }

    // ======================= token không hợp lệ =======================

    @ParameterizedTest(name = "refreshToken = [{0}]")
    @ValueSource(strings = { "", "   ", "khong-phai-jwt", "a.b.c", "eyJhbGciOiJIUzI1NiJ9.e30." })
    @DisplayName("Logout bằng token rác hoặc rỗng => 401, không lộ chi tiết, phiên thật còn nguyên")
    void logout_garbageToken_isRejected_andSessionSurvives(String garbage) throws Exception {
        Tokens tokens = loginOk(EMAIL, PASSWORD);

        MvcResult result = logoutRaw(garbage);

        assertThat(statusOf(result)).isEqualTo(401);
        assertThat(body(result)).doesNotContain("Exception").doesNotContain("at com.");
        if (!garbage.isBlank()) {
            assertThat(body(result)).doesNotContain(garbage);
        }
        assertSessionSurvives(tokens);
    }

    @ParameterizedTest(name = "body = {0}")
    @ValueSource(strings = { "{}", "{\"refreshToken\":null}", "[]", "\"x\"", "", "{\"refreshToken\":{\"a\":1}}" })
    @DisplayName("Body logout sai cấu trúc hoặc thiếu refreshToken => lỗi 4xx, không bao giờ 500")
    void logout_malformedBody_neverCauses500(String json) throws Exception {
        Tokens tokens = loginOk(EMAIL, PASSWORD);

        MvcResult result = postJson(LOGOUT_URL, json);

        assertThat(statusOf(result)).isBetween(400, 499);
        assertThat(body(result)).doesNotContain("Exception").doesNotContain("at com.");
        assertSessionSurvives(tokens);
    }

    @Test
    @DisplayName("Logout bằng refresh token bị sửa 'sub' sang email khác (giữ chữ ký cũ) => 401, phiên thật còn nguyên")
    void logout_tamperedToken_isRejected() throws Exception {
        Tokens tokens = loginOk(EMAIL, PASSWORD);
        String tampered = tamperPayload(tokens.refresh(), EMAIL, "admin@gmail.com");

        assertThat(statusOf(logoutRaw(tampered))).isEqualTo(401);

        assertSessionSurvives(tokens);
    }

    @Test
    @DisplayName("Logout bằng token ký sai khoá (kể cả đúng email và jti thật) => 401, phiên thật còn nguyên")
    void logout_tokenSignedWithWrongKey_isRejected() throws Exception {
        Tokens tokens = loginOk(EMAIL, PASSWORD);
        String realJti = jwtService.extractJti(tokens.refresh());
        String forged = forgeToken("another-secret-another-secret-another-123", EMAIL, claims(realJti),
                new Date(System.currentTimeMillis() + 60_000));

        assertThat(statusOf(logoutRaw(forged))).isEqualTo(401);

        assertSessionSurvives(tokens);
    }

    @Test
    @DisplayName("Logout bằng token đã hết hạn (dù Redis còn phiên) => 401, phiên còn nguyên")
    void logout_expiredToken_isRejected() throws Exception {
        Tokens tokens = loginOk(EMAIL, PASSWORD);
        String realJti = jwtService.extractJti(tokens.refresh());
        String expired = forgeToken(JWT_SECRET, EMAIL, claims(realJti), new Date(System.currentTimeMillis() - 60_000));

        assertThat(statusOf(logoutRaw(expired))).isEqualTo(401);

        assertSessionSurvives(tokens);
    }

    @Test
    @DisplayName("Token ký đúng khoá và đúng jti nhưng KHÁC token thật (băm không khớp) => 401, phiên thật còn nguyên")
    void logout_validSignatureButHashMismatch_isRejected() throws Exception {
        Tokens tokens = loginOk(EMAIL, PASSWORD);
        String realJti = jwtService.extractJti(tokens.refresh());
        String lookalike = forgeToken(JWT_SECRET, EMAIL, claims(realJti),
                new Date(System.currentTimeMillis() + 60_000));

        assertThat(statusOf(logoutRaw(lookalike))).isEqualTo(401);

        assertSessionSurvives(tokens);
    }

    @Test
    @DisplayName("Token ký đúng khoá nhưng jti không tồn tại trong Redis => 401, phiên thật còn nguyên")
    void logout_unknownJti_isRejected() throws Exception {
        Tokens tokens = loginOk(EMAIL, PASSWORD);
        String forged = forgeToken(JWT_SECRET, EMAIL, claims("jti-khong-ton-tai"),
                new Date(System.currentTimeMillis() + 60_000));

        assertThat(statusOf(logoutRaw(forged))).isEqualTo(401);

        assertSessionSurvives(tokens);
    }

    // ======================= phạm vi tác động =======================

    @Test
    @DisplayName("Logout bằng refresh token CŨ của phiên đã bị đăng nhập mới thay thế => 401 và KHÔNG phá phiên hiện tại")
    void logout_staleTokenFromReplacedSession_cannotKillCurrentSession() throws Exception {
        Tokens stale = loginOk(EMAIL, PASSWORD);
        Tokens current = loginOk(EMAIL, PASSWORD);

        assertThat(statusOf(logoutRaw(stale.refresh()))).isEqualTo(401);

        assertSessionSurvives(current);
    }

    @Test
    @DisplayName("Logout của user A không ảnh hưởng phiên của user B")
    void logout_doesNotAffectOtherUsers() throws Exception {
        createUser("binh@gmail.com", PASSWORD, Role.USER, 1, 1);
        Tokens a = loginOk(EMAIL, PASSWORD);
        Tokens b = loginOk("binh@gmail.com", PASSWORD);

        assertThat(statusOf(logoutRaw(a.refresh()))).isEqualTo(200);

        assertThat(sessionKeys(EMAIL)).isEmpty();
        assertThat(sessionKeys("binh@gmail.com")).hasSize(1);
        assertThat(statusOf(getWithToken(USER_URL, b.access()))).isEqualTo(200);
        refreshOk(b.refresh());
    }

    // ======================= sau khi logout =======================

    @Test
    @DisplayName("Sau logout, không token nào của chuỗi xoay vòng (kể cả đời đầu) tạo lại được phiên")
    void logout_afterRotation_noOldTokenCanRevive() throws Exception {
        Tokens first = loginOk(EMAIL, PASSWORD);
        Tokens second = refreshOk(first.refresh());

        assertThat(statusOf(logoutRaw(second.refresh()))).isEqualTo(200);

        assertThat(sessionKeys(EMAIL)).isEmpty();
        assertThat(statusOf(refreshRaw(first.refresh()))).isEqualTo(401);
        assertThat(statusOf(refreshRaw(second.refresh()))).isEqualTo(401);
        assertThat(sessionKeys(EMAIL)).isEmpty();
        assertThat(statusOf(getWithToken(USER_URL, second.access()))).isEqualTo(401);
    }

    @Test
    @DisplayName("Logout là endpoint công khai: header Authorization rác không làm hỏng việc đăng xuất")
    void logout_withGarbageAuthorizationHeader_stillWorks() throws Exception {
        Tokens tokens = loginOk(EMAIL, PASSWORD);

        MvcResult result = mockMvc.perform(post(LOGOUT_URL)
                .header("Authorization", "Bearer rac")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + tokens.refresh() + "\"}"))
                .andReturn();

        assertThat(statusOf(result)).as(body(result)).isEqualTo(200);
        assertThat(sessionKeys(EMAIL)).isEmpty();
    }

    @Test
    @DisplayName("Logout thành công không đặt cookie và không trả token")
    void logout_success_setsNoCookiesAndReturnsNoTokens() throws Exception {
        Tokens tokens = loginOk(EMAIL, PASSWORD);

        MvcResult result = logoutRaw(tokens.refresh());

        assertThat(statusOf(result)).isEqualTo(200);
        assertThat(result.getResponse().getHeaders("Set-Cookie")).isEmpty();
        assertThat(body(result)).doesNotContain("accessToken").doesNotContain("refreshToken");
    }

    // ======================= race =======================

    @RepeatedTest(5)
    @DisplayName("Race: 8 request logout đồng thời cùng một refresh token => đúng 1 trả 200, 7 trả 401, phiên bị xoá")
    void logout_concurrentSameToken_exactlyOneSucceeds() throws Exception {
        Tokens tokens = loginOk(EMAIL, PASSWORD);

        List<Integer> statuses = runConcurrently(8, () -> statusOf(logoutRaw(tokens.refresh())));

        assertThat(statuses.stream().filter(s -> s == 200).count()).isEqualTo(1);
        assertThat(statuses.stream().filter(s -> s == 401).count()).isEqualTo(7);
        assertThat(sessionKeys(EMAIL)).isEmpty();
        assertThat(statusOf(refreshRaw(tokens.refresh()))).isEqualTo(401);
    }
}