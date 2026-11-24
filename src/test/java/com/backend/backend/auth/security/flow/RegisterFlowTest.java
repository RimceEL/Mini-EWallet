package com.backend.backend.auth.security.flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.matches;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.MvcResult;

import com.backend.backend.auth.security.SecurityIntegrationTestBase;

public class RegisterFlowTest extends SecurityIntegrationTestBase {

    private static final String EMAIL = "moi@gmail.com";
    private static final String USERNAME = "nguoidung01";
    private static final String FULL_NAME = "Nguyen Van An";
    private static final String REGISTER_URL = "/api/v1/auth/register";

    private MvcResult register(String email, String username, String password, String fullName) throws Exception {
        return postJson(REGISTER_URL,
                "{\"email\":\"" + email + "\",\"username\":\"" + username + "\",\"password\":\"" + password
                        + "\",\"fullName\":\"" + fullName + "\"}");
    }

    private MvcResult registerDefault(String email) throws Exception {
        return register(email, USERNAME, PASSWORD, FULL_NAME);
    }

    private int countUsers(String email) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE email = ?", Integer.class, email);
        return count == null ? 0 : count;
    }

    private String column(String column, String email) {
        return jdbc.queryForObject("SELECT " + column + " FROM users WHERE email = ?", String.class, email);
    }

    // ======================= happy path =======================

    @Test
    @DisplayName("Đăng ký thành công: user chưa xác thực, đang hoạt động, role USER, mật khẩu là hash BCrypt, dữ liệu đã chuẩn hoá")
    void register_success_createsUnverifiedUserWithHashedPassword() throws Exception {
        MvcResult result = registerDefault(EMAIL);

        assertThat(statusOf(result)).as(body(result)).isEqualTo(200);
        assertThat(countUsers(EMAIL)).isEqualTo(1);
        assertThat(column("is_verified", EMAIL)).isEqualTo("0");
        assertThat(column("is_active", EMAIL)).isEqualTo("1");
        assertThat(column("role", EMAIL)).isEqualTo("USER");
        assertThat(column("username", EMAIL)).isEqualTo(USERNAME);
        assertThat(column("full_name", EMAIL)).isEqualTo(FULL_NAME);
        String stored = column("password", EMAIL);
        assertThat(stored).startsWith("$2").isNotEqualTo(PASSWORD);
        assertThat(passwordEncoder.matches(PASSWORD, stored)).isTrue();
    }

    @Test
    @DisplayName("Đăng ký không tự đăng nhập: không trả token, không tạo phiên, không lộ mật khẩu hay hash")
    void register_success_issuesNoTokensAndLeaksNothing() throws Exception {
        MvcResult result = registerDefault(EMAIL);

        assertThat(statusOf(result)).isEqualTo(200);
        assertThat(body(result)).doesNotContain("accessToken").doesNotContain("refreshToken")
                .doesNotContain(PASSWORD).doesNotContain("$2");
        assertThat(sessionKeys(EMAIL)).isEmpty();
        assertThat(result.getResponse().getHeaders("Set-Cookie")).isEmpty();
    }

    @Test
    @DisplayName("Đăng ký gửi đúng 1 mail xác thực (mã 6 chữ số) tới đúng email")
    void register_success_sendsVerificationMail() throws Exception {
        assertThat(statusOf(registerDefault(EMAIL))).isEqualTo(200);

        verify(emailService, timeout(5000)).sendVerificationCode(eq(EMAIL), matches("^\\d{6}$"));
        verify(emailService, after(300).times(1)).sendVerificationCode(anyString(), anyString());
    }

    @Test
    @DisplayName("Cùng một mật khẩu của hai user cho hai hash khác nhau (có salt)")
    void register_samePassword_producesDifferentHashes() throws Exception {
        assertThat(statusOf(registerDefault("mot@gmail.com"))).isEqualTo(200);
        assertThat(statusOf(registerDefault("hai@gmail.com"))).isEqualTo(200);

        assertThat(column("password", "mot@gmail.com")).isNotEqualTo(column("password", "hai@gmail.com"));
    }

    @Test
    @DisplayName("Chưa xác thực email thì chưa đăng nhập được, dù mật khẩu đúng")
    void register_thenLoginBeforeVerify_isBlocked() throws Exception {
        assertThat(statusOf(registerDefault(EMAIL))).isEqualTo(200);

        MvcResult login = loginRaw(EMAIL, PASSWORD);

        assertThat(statusOf(login)).isBetween(400, 499);
        assertThat(body(login)).doesNotContain("accessToken");
        assertThat(sessionKeys(EMAIL)).isEmpty();
    }

    // ======================= email trùng =======================

    @Test
    @DisplayName("Đăng ký trùng email (tuần tự) => 409, không ghi đè mật khẩu của tài khoản đầu, không gửi mail lần hai")
    void register_duplicateEmail_returns409_andKeepsOriginalAccount() throws Exception {
        assertThat(statusOf(registerDefault(EMAIL))).isEqualTo(200);
        verify(emailService, timeout(5000)).sendVerificationCode(eq(EMAIL), anyString());

        MvcResult duplicate = register(EMAIL, "ketancong01", "MatKhauKeTanCong!99", "Ke Tan Cong");

        assertThat(statusOf(duplicate)).as(body(duplicate)).isEqualTo(409);
        assertThat(countUsers(EMAIL)).isEqualTo(1);
        assertThat(passwordEncoder.matches(PASSWORD, column("password", EMAIL))).isTrue();
        assertThat(column("username", EMAIL)).isEqualTo(USERNAME);
        verify(emailService, after(300).times(1)).sendVerificationCode(anyString(), anyString());
    }

    @Test
    @DisplayName("Trùng email khác hoa/thường hoặc thừa khoảng trắng cũng bị coi là trùng => 409")
    void register_duplicateEmailWithDifferentCase_returns409() throws Exception {
        assertThat(statusOf(registerDefault(EMAIL))).isEqualTo(200);

        MvcResult upper = registerDefault("MOI@GMAIL.COM");
        MvcResult padded = registerDefault("  moi@gmail.com  ");

        assertThat(statusOf(upper)).as(body(upper)).isEqualTo(409);
        assertThat(statusOf(padded)).as(body(padded)).isEqualTo(409);
        assertThat(countUsers(EMAIL)).isEqualTo(1);
    }

    // ======================= đầu vào không hợp lệ qua endpoint
    // =======================

    @ParameterizedTest(name = "email = [{0}]")
    @ValueSource(strings = { "plainaddress", "an@@gmail.com", "@gmail.com", "an@", "an gmail@x.com",
            "an@gmail.com,evil@x.com" })
    @DisplayName("Email sai định dạng => 400, không tạo user, không gửi mail")
    void register_invalidEmail_isRejected(String email) throws Exception {
        MvcResult result = registerDefault(email);

        assertThat(statusOf(result)).as(body(result)).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users", Integer.class)).isZero();
        verifyNoInteractions(emailService);
    }

    @ParameterizedTest(name = "password = [{0}]")
    @ValueSource(strings = { "Short1!A", "khongcochuhoa!!!!", "KhongCoKyTuDacBiet123", "", "            " })
    @DisplayName("Mật khẩu yếu (ngắn, thiếu chữ hoa, thiếu ký tự đặc biệt, rỗng, toàn khoảng trắng) => 400, không tạo user, không gửi mail")
    void register_weakPassword_isRejected(String password) throws Exception {
        MvcResult result = register(EMAIL, USERNAME, password, FULL_NAME);

        assertThat(statusOf(result)).as(body(result)).isEqualTo(400);
        assertThat(countUsers(EMAIL)).isZero();
        verifyNoInteractions(emailService);
    }

    @ParameterizedTest(name = "body = {0}")
    @ValueSource(strings = {
            "{}",
            "{\"email\":null,\"username\":null,\"password\":null,\"fullName\":null}",
            "{\"email\":\"moi@gmail.com\"}",
            "{\"email\":{\"a\":1},\"username\":\"nguoidung01\",\"password\":\"x\",\"fullName\":\"Nguyen Van An\"}",
            "[]",
            "\"x\"",
            "",
            "{" })
    @DisplayName("Body đăng ký sai cấu trúc hoặc thiếu trường => lỗi 4xx, không bao giờ 500, không tạo user")
    void register_malformedBody_neverCauses5xx(String json) throws Exception {
        MvcResult result = postJson(REGISTER_URL, json);

        assertThat(statusOf(result)).as(abbreviate(body(result))).isBetween(400, 499);
        assertThat(body(result)).doesNotContain("Exception").doesNotContain("at com.");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users", Integer.class)).isZero();
        verifyNoInteractions(emailService);
    }

    // ======================= mass assignment =======================

    @Test
    @DisplayName("Mass assignment: gửi kèm role=ADMIN, isVerified, isActive, id... bị bỏ qua hoặc bị từ chối; user vẫn là USER chưa xác thực")
    void register_extraPrivilegedFields_areIgnoredOrRejected() throws Exception {
        String forged = "{\"email\":\"" + EMAIL + "\",\"username\":\"" + USERNAME + "\",\"password\":\""
                + PASSWORD + "\",\"fullName\":\"" + FULL_NAME + "\","
                + "\"role\":\"ADMIN\",\"isVerified\":1,\"isActive\":1,\"is_verified\":1,\"is_active\":1,"
                + "\"verified\":true,\"enabled\":true,\"id\":\"attacker-id\"}";

        MvcResult result = postJson(REGISTER_URL, forged);

        int status = statusOf(result);
        assertThat(status).as(body(result)).isIn(200, 400);
        if (status == 200) {
            assertThat(column("role", EMAIL)).isEqualTo("USER");
            assertThat(column("is_verified", EMAIL)).isEqualTo("0");
            assertThat(column("id", EMAIL)).isNotEqualTo("attacker-id");
            assertThat(statusOf(loginRaw(EMAIL, PASSWORD))).as("chưa xác thực nên chưa đăng nhập được")
                    .isBetween(400, 499);
        } else {
            assertThat(countUsers(EMAIL)).isZero();
        }
    }

    private static String abbreviate(String text) {
        return text.length() <= 200 ? text : text.substring(0, 200) + "...";
    }
}