package com.backend.backend.auth.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailSendException;
import org.springframework.test.web.servlet.MvcResult;

import com.backend.backend.auth.model.Role;

public class PasswordResetLogicTest extends SecurityIntegrationTestBase {

    private static final String EMAIL = "an@gmail.com";
    private static final String NEW_PASSWORD = "MatKhauMoiDuDai!2024";

    @BeforeEach
    void seedUser() {
        createUser(EMAIL, PASSWORD, Role.USER, 1, 1);
    }

    private String requestOtp() throws Exception {
        MvcResult result = postJson("/api/v1/auth/otp-reset-request", "{\"email\":\"" + EMAIL + "\"}");
        assertThat(statusOf(result)).as(body(result)).isEqualTo(200);
        ArgumentCaptor<String> otp = ArgumentCaptor.forClass(String.class);
        verify(emailService, timeout(5000)).sendPasswordResetOtp(eq(EMAIL), otp.capture(), anyInt());
        return otp.getValue();
    }

    private String exchangeOtpForToken(String otp) throws Exception {
        MvcResult result = postJson("/api/v1/auth/otp-reset-verify",
                "{\"email\":\"" + EMAIL + "\",\"code\":\"" + otp + "\"}");
        assertThat(statusOf(result)).as(body(result)).isEqualTo(200);
        return jsonString(result, "$.data.token");
    }

    private String obtainResetToken() throws Exception {
        return exchangeOtpForToken(requestOtp());
    }

    private MvcResult resetPassword(String token, String newPassword) throws Exception {
        return postJson("/api/v1/auth/otp-reset-password",
                "{\"email\":\"" + EMAIL + "\",\"token\":\"" + token + "\",\"newPassword\":\"" + newPassword + "\"}");
    }

    @Test
    @DisplayName("[GIẢ THUYẾT] Mail thông báo đổi mật khẩu bị lỗi thì việc đổi mật khẩu vẫn phải trả 200")
    void passwordChangedNoticeFailure_doesNotFailTheRequest() throws Exception {
        String token = obtainResetToken();
        doThrow(new MailSendException("SMTP down")).when(emailService).sendPasswordChangedNotice(anyString());

        MvcResult result = resetPassword(token, NEW_PASSWORD);

        assertThat(statusOf(result)).as(body(result)).isEqualTo(200);
    }

    @Test
    @DisplayName("[GIẢ THUYẾT] Mail thông báo lỗi: phản hồi phải khớp với trạng thái thật (200 <=> mật khẩu mới dùng được)")
    void passwordChangedNoticeFailure_responseMatchesRealState() throws Exception {
        String token = obtainResetToken();
        doThrow(new MailSendException("SMTP down")).when(emailService).sendPasswordChangedNotice(anyString());

        int resetStatus = statusOf(resetPassword(token, NEW_PASSWORD));
        boolean newPasswordWorks = statusOf(loginRaw(EMAIL, NEW_PASSWORD)) == 200;

        assertThat(resetStatus == 200)
                .as("reset trả " + resetStatus + " nhưng mật khẩu mới " + (newPasswordWorks ? "ĐÃ" : "CHƯA")
                        + " có hiệu lực")
                .isEqualTo(newPasswordWorks);
    }

    @ParameterizedTest(name = "[ký tự:số lần lặp sau tiền tố A!] {0}")
    @ValueSource(strings = { "a:71", "ệ:24", "a:99998" })
    @DisplayName("[GIẢ THUYẾT] Mật khẩu mới dài quá 72 byte bị từ chối 400 và KHÔNG làm cháy reset token")
    void tooLongNewPassword_isRejectedWithoutBurningTheToken(String spec) throws Exception {
        String[] parts = spec.split(":");
        String tooLong = "A!" + parts[0].repeat(Integer.parseInt(parts[1]));
        String token = obtainResetToken();

        MvcResult rejected = resetPassword(token, tooLong);

        assertThat(statusOf(rejected)).as("mật khẩu " + tooLong.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                + " byte: " + abbreviate(body(rejected))).isEqualTo(400);
        assertThat(statusOf(resetPassword(token, NEW_PASSWORD)))
                .as("token vẫn phải dùng lại được sau một lần gửi mật khẩu không hợp lệ").isEqualTo(200);
    }

    @Test
    @DisplayName("[Đối chứng] Mật khẩu mới đúng 72 byte vẫn đổi được, mật khẩu cũ hết hiệu lực")
    void newPasswordOfExactly72Bytes_isAccepted() throws Exception {
        String exactly72Bytes = "a".repeat(70) + "A*";
        String token = obtainResetToken();

        MvcResult result = resetPassword(token, exactly72Bytes);

        assertThat(statusOf(result)).as(body(result)).isEqualTo(200);
        assertThat(statusOf(loginRaw(EMAIL, exactly72Bytes))).isEqualTo(200);
        assertThat(statusOf(loginRaw(EMAIL, PASSWORD))).isEqualTo(401);
    }

    private static String abbreviate(String text) {
        return text.length() <= 200 ? text : text.substring(0, 200) + "...";
    }
}
