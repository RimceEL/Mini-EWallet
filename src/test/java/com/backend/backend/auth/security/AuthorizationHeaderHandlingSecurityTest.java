package com.backend.backend.auth.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import com.backend.backend.auth.model.Role;

import jakarta.servlet.http.Cookie;

public class AuthorizationHeaderHandlingSecurityTest extends SecurityIntegrationTestBase {

    private static final String EMAIL = "an@gmail.com";
    private static final String LOGIN_URL = "/api/v1/auth/login";
    private static final String REFRESH_URL = "/api/v1/auth/refresh-token";

    @BeforeEach
    void seedUser() {
        createUser(EMAIL, PASSWORD, Role.USER, 1, 1);
    }

    static Stream<String> garbageAuthorizationHeaders() {
        String expired = forgeToken(JWT_SECRET, EMAIL, claims("jti-het-han"),
                new Date(System.currentTimeMillis() - 60_000));
        return Stream.of(
                "Bearer rac",
                "Bearer ",
                "Bearer",
                "Bearer null",
                "Bearer undefined",
                "Bearer e30.e30.e30",
                "Basic YWRtaW46YWRtaW4=",
                "bearer abc",
                "Token abc",
                "Bearer " + expired,
                "Bearer " + "a".repeat(50_000));
    }

    @ParameterizedTest(name = "header #{index}")
    @MethodSource("garbageAuthorizationHeaders")
    @DisplayName("Header Authorization rác gửi kèm khi ĐĂNG NHẬP => vẫn đăng nhập được (endpoint công khai không bị chặn)")
    void garbageAuthorizationHeader_doesNotBreakLogin(String authorization) throws Exception {
        MvcResult result = mockMvc.perform(post(LOGIN_URL)
                .header("Authorization", authorization)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + EMAIL + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andReturn();

        assertThat(statusOf(result)).as(body(result)).isEqualTo(200);
        assertThat(body(result)).contains("accessToken");
    }

    @ParameterizedTest(name = "header #{index}")
    @MethodSource("garbageAuthorizationHeaders")
    @DisplayName("Header Authorization rác gửi kèm khi REFRESH => vẫn refresh được bằng refresh token hợp lệ")
    void garbageAuthorizationHeader_doesNotBreakRefresh(String authorization) throws Exception {
        Tokens tokens = loginOk(EMAIL, PASSWORD);

        MvcResult result = mockMvc.perform(post(REFRESH_URL)
                .header("Authorization", authorization)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + tokens.refresh() + "\"}"))
                .andReturn();

        assertThat(statusOf(result)).as(body(result)).isEqualTo(200);
    }

    @ParameterizedTest(name = "header #{index}")
    @MethodSource("garbageAuthorizationHeaders")
    @DisplayName("Header Authorization rác trên endpoint được bảo vệ => 401, không bao giờ 5xx, không lộ chi tiết")
    void garbageAuthorizationHeader_onProtectedEndpoint_isUnauthorized(String authorization) throws Exception {
        MvcResult result = mockMvc.perform(get(USER_URL).header("Authorization", authorization)).andReturn();

        assertThat(statusOf(result)).isEqualTo(401);
        assertThat(body(result)).doesNotContain("Exception").doesNotContain("at com.").doesNotContain("data");
    }

    @Test
    @DisplayName("Token gần đúng (thêm/bớt/đổi 1 ký tự, thừa khoảng trắng, thừa đoạn) đều bị từ chối 401")
    void almostValidTokens_areAllRejected() throws Exception {
        String token = loginOk(EMAIL, PASSWORD).access();
        int mid = token.length() - 10; // nằm trong phần chữ ký, đổi ký tự ở đây chắc chắn đổi nội dung chữ ký
        char swapped = token.charAt(mid) == 'A' ? 'B' : 'A';
        List<String> variants = List.of(
                " " + token,
                token + " ",
                token + "x",
                token + ".extra",
                "x" + token,
                token.substring(0, token.length() - 1),
                token.substring(0, mid) + swapped + token.substring(mid + 1),
                token.replace('.', ','),
                token.toUpperCase());

        List<String> violations = new ArrayList<>();
        for (String variant : variants) {
            int status = statusOf(getWithToken(USER_URL, variant));
            if (status != 401) {
                violations
                        .add("token biến thể kết thúc bằng '..." + variant.substring(Math.max(0, variant.length() - 8))
                                + "' => " + status);
            }
        }

        assertThat(violations).isEmpty();
        assertThat(statusOf(getWithToken(USER_URL, token))).as("[Đối chứng] token gốc vẫn dùng được").isEqualTo(200);
    }

    @Test
    @DisplayName("Token hợp lệ gửi qua query string (?access_token=) hoặc cookie bị bỏ qua => 401")
    void tokenInWrongPlace_isIgnored() throws Exception {
        String token = loginOk(EMAIL, PASSWORD).access();

        MvcResult viaQuery = mockMvc.perform(get(USER_URL).param("access_token", token)).andReturn();
        MvcResult viaCookie = mockMvc.perform(get(USER_URL)
                .cookie(new Cookie("Authorization", "Bearer " + token))
                .cookie(new Cookie("access_token", token)))
                .andReturn();

        assertThat(statusOf(viaQuery)).isEqualTo(401);
        assertThat(statusOf(viaCookie)).isEqualTo(401);
    }

    @Test
    @DisplayName("Chữ ký hợp lệ nhưng claim roles rỗng => không phải access token, bị từ chối 401 NOT_USED_ACCESS_TOKEN")
    void validSignatureButEmptyRoles_isNotAnAccessToken() throws Exception {
        Tokens tokens = loginOk(EMAIL, PASSWORD);
        Map<String, Object> claims = claims(jwtService.extractJti(tokens.access()));
        claims.put("roles", List.of());
        String forged = forgeToken(JWT_SECRET, EMAIL, claims, new Date(System.currentTimeMillis() + 60_000));

        MvcResult result = getWithToken(USER_URL, forged);

        assertThat(statusOf(result)).isEqualTo(401);
        assertThat(jsonString(result, "$.code")).isEqualTo("NOT_USED_ACCESS_TOKEN");
    }

    @ParameterizedTest(name = "sub = [{0}]")
    @ValueSource(strings = { "AN@GMAIL.COM", " an@gmail.com", "an@gmail.com " })
    @DisplayName("Đổi hoa/thường hoặc thêm khoảng trắng vào 'sub' (kèm jti thật) không mượn được phiên của người khác")
    void subjectVariants_doNotAliasTheRealSession(String subject) throws Exception {
        Tokens tokens = loginOk(EMAIL, PASSWORD);
        Map<String, Object> claims = claims(jwtService.extractJti(tokens.access()));
        claims.put("roles", List.of(Map.of("authority", "ROLE_USER")));
        String forged = forgeToken(JWT_SECRET, subject, claims, new Date(System.currentTimeMillis() + 60_000));

        MvcResult result = getWithToken(USER_URL, forged);

        assertThat(statusOf(result)).isEqualTo(401);
        assertThat(jsonString(result, "$.code")).isEqualTo("TOKEN_REVOKED");
    }

    @Test
    @DisplayName("jti của người B ghép với email của người A không dùng được (phiên gắn chặt với email)")
    void jtiOfAnotherUser_cannotBeUsedWithMySubject() throws Exception {
        createUser("nguoi.khac@gmail.com", PASSWORD, Role.USER, 1, 1);
        Tokens other = loginOk("nguoi.khac@gmail.com", PASSWORD);
        Map<String, Object> claims = claims(jwtService.extractJti(other.access()));
        claims.put("roles", List.of(Map.of("authority", "ROLE_USER")));
        String forged = forgeToken(JWT_SECRET, EMAIL, claims, new Date(System.currentTimeMillis() + 60_000));

        MvcResult result = getWithToken(USER_URL, forged);

        assertThat(statusOf(result)).isEqualTo(401);
        assertThat(jsonString(result, "$.code")).isEqualTo("TOKEN_REVOKED");
    }
}
