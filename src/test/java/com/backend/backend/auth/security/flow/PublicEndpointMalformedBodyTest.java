package com.backend.backend.auth.security.flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.backend.backend.auth.model.Role;
import com.backend.backend.auth.security.SecurityIntegrationTestBase;

public class PublicEndpointMalformedBodyTest extends SecurityIntegrationTestBase {

    private static final String EMAIL = "an@gmail.com";

    private static final String VERIFY_URL = "/api/v1/auth/verify-email";
    private static final String RESEND_URL = "/api/v1/auth/resend-verification";
    private static final String RESET_REQUEST_URL = "/api/v1/auth/otp-reset-request";
    private static final String RESET_VERIFY_URL = "/api/v1/auth/otp-reset-verify";
    private static final String RESET_PASSWORD_URL = "/api/v1/auth/otp-reset-password";

    private static final List<String> ALL_URLS = List.of(
            VERIFY_URL, RESEND_URL, RESET_REQUEST_URL, RESET_VERIFY_URL, RESET_PASSWORD_URL);

    private static final List<String> BAD_BODIES = List.of(
            "{}",
            "[]",
            "null",
            "\"x\"",
            "123",
            "true",
            "",
            "{",
            "{\"email\":",
            "{\"email\":null}",
            "{\"email\":\"\"}",
            "{\"email\":\"   \"}",
            "{\"email\":123}",
            "{\"email\":true}",
            "{\"email\":[\"an@gmail.com\"]}",
            "{\"email\":{\"a\":1}}",
            "{\"email\":\"not-an-email\"}",
            "{\"email\":null,\"code\":\"123456\",\"token\":\"abc\",\"newPassword\":\"" + PASSWORD + "\"}");

    @BeforeEach
    void seedUser() {
        createUser(EMAIL, PASSWORD, Role.USER, 1, 1);
    }

    static Stream<Arguments> everyEndpointWithEveryBadBody() {
        List<Arguments> cases = new ArrayList<>();
        for (String url : ALL_URLS) {
            for (String json : BAD_BODIES) {
                cases.add(Arguments.of(url, json));
            }
        }
        return cases.stream();
    }

    private void assertRedisUntouched(String context) {
        Set<String> keys = redis.keys("*");
        assertThat(keys).as(context + " không được tạo bất kỳ key Redis nào").isNullOrEmpty();
    }

    private String passwordHashOf(String email) {
        return jdbc.queryForObject("SELECT password FROM users WHERE email = ?", String.class, email);
    }

    @ParameterizedTest(name = "{0} <- {1}")
    @MethodSource("everyEndpointWithEveryBadBody")
    @DisplayName("Body sai cấu trúc ở 5 endpoint công khai => 4xx, không bao giờ 5xx, không lộ chi tiết, không để lại key Redis (kể cả key '...:null')")
    void malformedBody_isRejectedCleanly_andLeavesRedisUntouched(String url, String json) throws Exception {
        MvcResult result = postJson(url, json);

        assertThat(statusOf(result)).as(url + " với body [" + json + "]: " + body(result)).isBetween(400, 499);
        assertThat(body(result)).doesNotContain("Exception").doesNotContain("at com.")
                .doesNotContain("org.springframework").doesNotContain("\"token\"");
        assertRedisUntouched(url + " với body [" + json + "]");
    }

    @Test
    @DisplayName("Body sai cấu trúc không làm gửi bất kỳ mail nào")
    void malformedBody_sendsNoMail() throws Exception {
        for (String url : ALL_URLS) {
            for (String json : BAD_BODIES) {
                postJson(url, json);
            }
        }

        verify(emailService, after(300).never()).sendPasswordResetOtp(any(), any(), anyInt());
        verify(emailService, never()).sendVerificationCode(any(), any());
        verify(emailService, never()).sendPasswordChangedNotice(any());
    }

    @Test
    @DisplayName("otp-reset-password với email null/thiếu nhưng token và mật khẩu hợp lệ => 4xx, mật khẩu cũ vẫn dùng được")
    void resetPassword_withoutEmail_changesNothing() throws Exception {
        String hashBefore = passwordHashOf(EMAIL);
        String newPassword = "MatKhauMoiMuoiHaiKyTu!2";
        List<String> bodies = List.of(
                "{\"token\":\"abc\",\"newPassword\":\"" + newPassword + "\"}",
                "{\"email\":null,\"token\":\"abc\",\"newPassword\":\"" + newPassword + "\"}",
                "{\"email\":\"\",\"token\":\"abc\",\"newPassword\":\"" + newPassword + "\"}",
                "{\"email\":\"   \",\"token\":\"abc\",\"newPassword\":\"" + newPassword + "\"}");

        for (String json : bodies) {
            MvcResult result = postJson(RESET_PASSWORD_URL, json);
            assertThat(statusOf(result)).as(json + " => " + body(result)).isBetween(400, 499);
        }

        assertThat(passwordHashOf(EMAIL)).as("hash mật khẩu không được đổi").isEqualTo(hashBefore);
        assertRedisUntouched("otp-reset-password thiếu email");
        assertThat(statusOf(loginRaw(EMAIL, PASSWORD))).isEqualTo(200);
        assertThat(statusOf(loginRaw(EMAIL, newPassword))).isEqualTo(401);
    }

    static Stream<Arguments> wrongTypedFieldsWithValidEmail() {
        String e = "\"email\":\"" + EMAIL + "\"";
        String goodPassword = "\"newPassword\":\"" + PASSWORD + "\"";
        List<Arguments> cases = new ArrayList<>();
        for (String url : List.of(VERIFY_URL, RESET_VERIFY_URL)) {
            for (String code : List.of("null", "123456", "true", "[\"123456\"]", "{\"a\":1}", "\"\"")) {
                cases.add(Arguments.of(url, "{" + e + ",\"code\":" + code + "}"));
            }
        }
        for (String token : List.of("null", "123", "true", "[\"abc\"]", "{\"a\":1}", "\"\"", "\"   \"")) {
            cases.add(Arguments.of(RESET_PASSWORD_URL, "{" + e + ",\"token\":" + token + "," + goodPassword + "}"));
        }
        for (String newPassword : List.of("null", "123456789012", "true", "[\"x\"]", "{\"a\":1}", "\"\"")) {
            cases.add(Arguments.of(RESET_PASSWORD_URL,
                    "{" + e + ",\"token\":\"abc\",\"newPassword\":" + newPassword + "}"));
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "{0} <- {1}")
    @MethodSource("wrongTypedFieldsWithValidEmail")
    @DisplayName("Email đúng nhưng code/token/newPassword sai kiểu hoặc thiếu => 4xx, không bao giờ 5xx, không phát token, không có key '...null'")
    void wrongTypedFields_withValidEmail_areRejectedCleanly(String url, String json) throws Exception {
        String hashBefore = passwordHashOf(EMAIL);

        MvcResult result = postJson(url, json);

        assertThat(statusOf(result)).as(url + " với body [" + json + "]: " + body(result)).isBetween(400, 499);
        assertThat(body(result)).doesNotContain("Exception").doesNotContain("at com.")
                .doesNotContain("\"token\"");
        assertThat(redis.keys("*null*")).as("không được có key chứa 'null'").isNullOrEmpty();
        assertThat(passwordHashOf(EMAIL)).isEqualTo(hashBefore);
    }

    @Test
    @DisplayName("Email khổng lồ / ký tự điều khiển / giả key Redis => 4xx nhanh, không tạo key Redis, không 5xx")
    void hostileEmails_areRejectedQuickly_andCreateNoRedisKey() throws Exception {
        List<String> emails = List.of(
                "a".repeat(100_000) + "@gmail.com",
                "an@" + "b".repeat(100_000) + ".com",
                "x".repeat(73) + "@g.com",
                "an@gmail.com\\u0000",
                "pwreset:resend:an@gmail.com",
                "an@gmail.com,evil@gmail.com",
                "an@gmail.com evil@gmail.com",
                "<script>alert(1)</script>@gmail.com",
                "an@gmail..com");

        for (String email : emails) {
            for (String url : ALL_URLS) {
                String json = "{\"email\":\"" + email + "\",\"code\":\"123456\",\"token\":\"abc\",\"newPassword\":\""
                        + PASSWORD + "\"}";
                long start = System.nanoTime();

                MvcResult result = postJson(url, json);

                long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
                String label = url + " với email dài " + email.length() + " ký tự: " + abbreviate(body(result));
                assertThat(statusOf(result)).as(label).isBetween(400, 499);
                assertThat(elapsedMs).as("xử lý email thù địch không được chậm: " + label).isLessThan(5_000);
            }
        }
        assertRedisUntouched("email thù địch");
    }

    @Test
    @DisplayName("Content-Type sai hoặc thiếu => 415, không 5xx, không tạo key Redis")
    void wrongContentType_isRejectedWith415() throws Exception {
        List<MediaType> mediaTypes = new ArrayList<>(List.of(
                MediaType.TEXT_PLAIN,
                MediaType.APPLICATION_FORM_URLENCODED,
                MediaType.APPLICATION_OCTET_STREAM));
        mediaTypes.add(null);

        for (String url : ALL_URLS) {
            for (MediaType mediaType : mediaTypes) {
                MockHttpServletRequestBuilder request = post(url).content("{\"email\":\"" + EMAIL + "\"}");
                if (mediaType != null) {
                    request.contentType(mediaType);
                }

                MvcResult result = mockMvc.perform(request).andReturn();

                assertThat(statusOf(result)).as(url + " với Content-Type " + mediaType + ": " + body(result))
                        .isEqualTo(415);
            }
        }
        assertRedisUntouched("Content-Type sai");
    }

    private static String abbreviate(String text) {
        return text.length() <= 200 ? text : text.substring(0, 200) + "...";
    }
}
