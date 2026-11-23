package com.backend.backend.auth.security.flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.MvcResult;

import com.backend.backend.auth.security.SecurityIntegrationTestBase;

import static org.junit.jupiter.api.Named.named;
import static org.junit.jupiter.params.provider.Arguments.arguments;

public class RegisterLogicTest extends SecurityIntegrationTestBase {

    private static final String EMAIL = "moi@gmail.com";
    private static final String USERNAME = "nguoidung01";
    private static final String FULL_NAME = "Nguyen Van An";

    private MvcResult register(String email, String username, String password, String fullName) throws Exception {
        return postJson("/api/v1/auth/register",
                "{\"email\":\"" + email + "\",\"username\":\"" + username + "\",\"password\":\"" + password
                        + "\",\"fullName\":\"" + fullName + "\"}");
    }

    private int countUsersWithEmail(String email) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE email = ?", Integer.class, email);
        return count == null ? 0 : count;
    }

    @Test
    @DisplayName("[GIẢ THUYẾT] 10 request đăng ký song song cùng một email (5 vòng): đúng 1 thành công, còn lại đều 409, không có 500")
    void concurrentRegistrationOfSameEmail_oneWinsAndOthersGet409() throws Exception {
        List<String> violations = new ArrayList<>();

        for (int round = 1; round <= 5; round++) {
            String email = "dua" + round + "@gmail.com";

            List<Integer> statuses = runConcurrently(10,
                    () -> statusOf(register(email, USERNAME, PASSWORD, FULL_NAME)));

            long created = statuses.stream().filter(s -> s == 200).count();
            long conflicts = statuses.stream().filter(s -> s == 409).count();
            if (created != 1 || conflicts != 9 || countUsersWithEmail(email) != 1) {
                violations.add("vòng " + round + " (" + email + "): trạng thái = " + statuses
                        + ", số user trong DB = " + countUsersWithEmail(email));
            }
        }

        assertThat(violations).as("mong đợi mỗi vòng đúng 1 lần 200 và 9 lần 409").isEmpty();
    }

    static Stream<Arguments> oversizedInputs() {
        String longLocalPartEmail = "a".repeat(250) + "@gmail.com";
        return Stream.of(
                arguments(named("email dài 260 ký tự (vượt varchar 255)", new Input(longLocalPartEmail, USERNAME,
                        PASSWORD, FULL_NAME))),
                arguments(named("username dài 300 ký tự", new Input("u300@gmail.com", "u".repeat(300),
                        PASSWORD, FULL_NAME))),
                arguments(named("fullName dài 300 ký tự", new Input("f300@gmail.com", USERNAME,
                        PASSWORD, "N".repeat(300)))),
                arguments(named("mật khẩu 73 byte ASCII (vượt giới hạn 72 byte của BCrypt)", new Input(
                        "p73@gmail.com", USERNAME, "a".repeat(73), FULL_NAME))),
                arguments(named("mật khẩu 25 ký tự tiếng Việt = 75 byte (đếm byte, không đếm ký tự)", new Input(
                        "p75@gmail.com", USERNAME, "ệ".repeat(25), FULL_NAME))),
                arguments(named("mật khẩu 100.000 ký tự", new Input("p100k@gmail.com", USERNAME,
                        "a".repeat(100_000), FULL_NAME))));
    }

    private record Input(String email, String username, String password, String fullName) {
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("oversizedInputs")
    @DisplayName("[GIẢ THUYẾT] Dữ liệu đăng ký quá dài bị từ chối 400, không tạo user, không gửi mail")
    void oversizedRegistration_isRejectedWith400(Input input) throws Exception {
        MvcResult result = register(input.email(), input.username(), input.password(), input.fullName());

        assertThat(statusOf(result)).as(abbreviate(body(result))).isEqualTo(400);
        assertThat(countUsersWithEmail(input.email())).as("không được tạo user").isZero();
        verifyNoInteractions(emailService);
    }

    @Test
    @DisplayName("[Đối chứng] Mật khẩu đúng 72 byte đăng ký được và đăng nhập lại bằng chính mật khẩu đó")
    void passwordOfExactly72Bytes_canRegisterAndLogin() throws Exception {
        String password = "a".repeat(70) + "A*";
        MvcResult registered = register(EMAIL, USERNAME, password, FULL_NAME);
        assertThat(statusOf(registered)).as(body(registered)).isEqualTo(200);
        setVerified(EMAIL, 1);

        assertThat(statusOf(loginRaw(EMAIL, password))).isEqualTo(200);
    }

    private static String abbreviate(String text) {
        return text.length() <= 200 ? text : text.substring(0, 200) + "...";
    }

    private String userColumn(String column, String email) {
        return jdbc.queryForObject("SELECT " + column + " FROM users WHERE email = ?", String.class, email);
    }

    @Test
    @DisplayName("[GIẢ THUYẾT] Username và fullName thừa khoảng trắng hai đầu và ở giữa => đăng ký được, DB lưu bản đã chuẩn hoá")
    void register_extraWhitespace_isStoredNormalized() throws Exception {
        MvcResult result = register(EMAIL, "  nguoidung01  ", PASSWORD, "  Nguyen   Van   An  ");

        assertThat(statusOf(result)).as(body(result)).isEqualTo(200);
        assertThat(userColumn("username", EMAIL)).isEqualTo("nguoidung01");
        assertThat(userColumn("full_name", EMAIL)).isEqualTo("Nguyen Van An");
    }

    @Test
    @DisplayName("[Đối chứng] Email viết hoa và thừa khoảng trắng => DB lưu email chữ thường, đã strip")
    void register_emailCaseAndWhitespace_isStoredNormalized() throws Exception {
        MvcResult result = register("  MOI@GMAIL.COM  ", USERNAME, PASSWORD, FULL_NAME);

        assertThat(statusOf(result)).as(body(result)).isEqualTo(200);
        assertThat(countUsersWithEmail("moi@gmail.com")).isEqualTo(1);
    }

    @ParameterizedTest(name = "username = [{0}]")
    @ValueSource(strings = { "      ", "  abc  ", "\u3000\u3000\u3000\u3000\u3000\u3000" })
    @DisplayName("Username toàn khoảng trắng (kể cả khoảng trắng Unicode) hoặc ngắn hơn 6 ký tự sau khi strip => 400, không tạo user, không gửi mail")
    void register_blankOrShortUsername_isRejected(String username) throws Exception {
        MvcResult result = register("blank-u@gmail.com", username, PASSWORD, FULL_NAME);

        assertThat(statusOf(result)).as(abbreviate(body(result))).isEqualTo(400);
        assertThat(countUsersWithEmail("blank-u@gmail.com")).isZero();
        verifyNoInteractions(emailService);
    }

    @ParameterizedTest(name = "fullName = [{0}]")
    @ValueSource(strings = { "      ", "  An  ", "\u3000\u3000\u3000\u3000\u3000\u3000" })
    @DisplayName("Họ tên toàn khoảng trắng (kể cả khoảng trắng Unicode) hoặc ngắn hơn 6 ký tự sau khi strip => 400, không tạo user, không gửi mail")
    void register_blankOrShortFullName_isRejected(String fullName) throws Exception {
        MvcResult result = register("blank-f@gmail.com", USERNAME, PASSWORD, fullName);

        assertThat(statusOf(result)).as(abbreviate(body(result))).isEqualTo(400);
        assertThat(countUsersWithEmail("blank-f@gmail.com")).isZero();
        verifyNoInteractions(emailService);
    }

    @Test
    @DisplayName("[GIẢ THUYẾT] Độn khoảng trắng giữa chữ không giúp vượt ngưỡng 6 ký tự: 'a    b    c' chỉ còn 5 ký tự sau khi chuẩn hoá => 400")
    void register_paddingBetweenWords_doesNotBypassMinLength() throws Exception {
        MvcResult username = register("pad-u@gmail.com", "a    b    c", PASSWORD, FULL_NAME);
        MvcResult fullName = register("pad-f@gmail.com", USERNAME, PASSWORD, "A    B    C");

        assertThat(statusOf(username)).as(abbreviate(body(username))).isEqualTo(400);
        assertThat(statusOf(fullName)).as(abbreviate(body(fullName))).isEqualTo(400);
        assertThat(countUsersWithEmail("pad-u@gmail.com")).isZero();
        assertThat(countUsersWithEmail("pad-f@gmail.com")).isZero();
    }
}
