package com.backend.backend.auth.security.flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.web.servlet.MvcResult;

import com.backend.backend.auth.model.Role;
import com.backend.backend.auth.security.SecurityIntegrationTestBase;

public class EmailVerificationFlowTest extends SecurityIntegrationTestBase {

    private static final String EMAIL = "moi@gmail.com";
    private static final String USERNAME = "nguoidung01";
    private static final String FULL_NAME = "Nguyen Van An";

    private static final String CODE_PREFIX = "email_verify:code:";
    private static final String ATTEMPTS_PREFIX = "email_verify:attempts:";
    private static final String RESEND_PREFIX = "email_verify:resend:attempts:";
    private static final String REFUND_PREFIX = "email_verify:refund:attempts:";

    private static final String VERIFY_URL = "/api/v1/auth/verify-email";
    private static final String RESEND_URL = "/api/v1/auth/resend-verification";

    @Value("${app.verification.max-attempts:5}")
    private int maxAttempts;
    @Value("${app.verification.code.resend.max-attempts:5}")
    private int maxResendAttempts;

    // ======================= helpers =======================

    private MvcResult register(String email) throws Exception {
        return postJson("/api/v1/auth/register",
                "{\"email\":\"" + email + "\",\"username\":\"" + USERNAME + "\",\"password\":\"" + PASSWORD
                        + "\",\"fullName\":\"" + FULL_NAME + "\"}");
    }

    private MvcResult verifyEmail(String email, String code) throws Exception {
        return postJson(VERIFY_URL, "{\"email\":\"" + email + "\",\"code\":\"" + code + "\"}");
    }

    private MvcResult resend(String email) throws Exception {
        return postJson(RESEND_URL, "{\"email\":\"" + email + "\"}");
    }

    private String registerAndGetCode(String email) throws Exception {
        MvcResult result = register(email);
        assertThat(statusOf(result)).as(body(result)).isEqualTo(200);
        return awaitValue(CODE_PREFIX + email);
    }

    private String awaitValue(String key) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            String value = redis.opsForValue().get(key);
            if (value != null) {
                return value;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("Key Redis không xuất hiện: " + key);
    }

    private void awaitEquals(String key, String expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        String last = null;
        while (System.currentTimeMillis() < deadline) {
            last = redis.opsForValue().get(key);
            if (expected.equals(last)) {
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("Key " + key + " mong đợi " + expected + " nhưng là " + last);
    }

    private Integer isVerified(String email) {
        return jdbc.queryForObject("SELECT is_verified FROM users WHERE email = ?", Integer.class, email);
    }

    private static String wrongCodeFor(String code) {
        return "000000".equals(code) ? "111111" : "000000";
    }

    // ======================= luồng đầy đủ =======================

    @Test
    @DisplayName("Luồng đầy đủ: đăng ký => mail chứa đúng mã => login bị chặn => verify => login được, key Redis được dọn")
    void fullFlow_registerVerifyThenLogin() throws Exception {
        String code = registerAndGetCode(EMAIL);

        assertThat(code).matches("^\\d{6}$");
        assertThat(redis.getExpire(CODE_PREFIX + EMAIL, TimeUnit.SECONDS)).isBetween(1L, 600L);
        ArgumentCaptor<String> mailed = ArgumentCaptor.forClass(String.class);
        verify(emailService, timeout(5000)).sendVerificationCode(eq(EMAIL), mailed.capture());
        assertThat(mailed.getValue()).isEqualTo(code);
        assertThat(isVerified(EMAIL)).isZero();

        MvcResult before = loginRaw(EMAIL, PASSWORD);
        assertThat(statusOf(before)).isBetween(400, 499);
        assertThat(body(before)).doesNotContain("accessToken");
        assertThat(sessionKeys(EMAIL)).isEmpty();

        MvcResult verified = verifyEmail(EMAIL, code);

        assertThat(statusOf(verified)).as(body(verified)).isEqualTo(200);
        assertThat(isVerified(EMAIL)).isEqualTo(1);
        assertThat(redis.hasKey(CODE_PREFIX + EMAIL)).isFalse();
        assertThat(redis.hasKey(ATTEMPTS_PREFIX + EMAIL)).isFalse();
        assertThat(statusOf(loginRaw(EMAIL, PASSWORD))).isEqualTo(200);
    }

    @Test
    @DisplayName("Mã đã dùng thành công thì không dùng lại được")
    void verify_codeCannotBeReusedAfterSuccess() throws Exception {
        String code = registerAndGetCode(EMAIL);
        assertThat(statusOf(verifyEmail(EMAIL, code))).isEqualTo(200);

        MvcResult replay = verifyEmail(EMAIL, code);

        assertThat(statusOf(replay)).as(body(replay)).isEqualTo(400);
    }

    // ======================= sai mã / hết hạn =======================

    @Test
    @DisplayName("Sai mã => 400, user vẫn chưa xác thực, mã thật vẫn dùng được sau đó")
    void verify_wrongCode_returns400_andRealCodeStillWorks() throws Exception {
        String code = registerAndGetCode(EMAIL);

        MvcResult wrong = verifyEmail(EMAIL, wrongCodeFor(code));

        assertThat(statusOf(wrong)).as(body(wrong)).isEqualTo(400);
        assertThat(isVerified(EMAIL)).isZero();
        assertThat(statusOf(verifyEmail(EMAIL, code))).isEqualTo(200);
        assertThat(isVerified(EMAIL)).isEqualTo(1);
    }

    @Test
    @DisplayName("Sai quá maxAttempts => 429 kể cả khi nhập đúng; yêu cầu gửi lại mã thì mở khoá")
    void verify_tooManyWrongAttempts_locksOut_andResendUnlocks() throws Exception {
        String code = registerAndGetCode(EMAIL);
        String wrong = wrongCodeFor(code);

        for (int i = 0; i < maxAttempts; i++) {
            assertThat(statusOf(verifyEmail(EMAIL, wrong))).as("lần sai " + i).isEqualTo(400);
        }
        MvcResult locked = verifyEmail(EMAIL, code);
        assertThat(statusOf(locked)).as(body(locked)).isEqualTo(429);
        assertThat(isVerified(EMAIL)).isZero();

        MvcResult resent = resend(EMAIL);
        assertThat(statusOf(resent)).as(body(resent)).isEqualTo(200);
        assertThat(redis.hasKey(ATTEMPTS_PREFIX + EMAIL)).as("bộ đếm sai phải được reset").isFalse();

        String newCode = redis.opsForValue().get(CODE_PREFIX + EMAIL);
        assertThat(newCode).isNotNull();
        assertThat(statusOf(verifyEmail(EMAIL, newCode))).isEqualTo(200);
        assertThat(isVerified(EMAIL)).isEqualTo(1);
    }

    @Test
    @DisplayName("Nhập đúng ở lượt cuối còn được phép (lượt thứ maxAttempts) vẫn thành công")
    void verify_correctCodeOnLastAllowedAttempt_succeeds() throws Exception {
        String code = registerAndGetCode(EMAIL);
        String wrong = wrongCodeFor(code);
        for (int i = 0; i < maxAttempts - 1; i++) {
            assertThat(statusOf(verifyEmail(EMAIL, wrong))).isEqualTo(400);
        }

        MvcResult last = verifyEmail(EMAIL, code);

        assertThat(statusOf(last)).as(body(last)).isEqualTo(200);
    }

    @Test
    @DisplayName("Mã hết hạn (mất key Redis) => 400; gửi lại mã thì xác thực được")
    void verify_expiredCode_returns400_andResendRecovers() throws Exception {
        String code = registerAndGetCode(EMAIL);
        redis.delete(CODE_PREFIX + EMAIL);

        MvcResult expired = verifyEmail(EMAIL, code);
        assertThat(statusOf(expired)).as(body(expired)).isEqualTo(400);

        assertThat(statusOf(resend(EMAIL))).isEqualTo(200);
        String newCode = redis.opsForValue().get(CODE_PREFIX + EMAIL);
        assertThat(statusOf(verifyEmail(EMAIL, newCode))).isEqualTo(200);
    }

    @Test
    @DisplayName("Email không tồn tại cho phản hồi GIỐNG HỆT mã hết hạn của email thật (chống dò email)")
    void verify_unknownEmail_looksIdenticalToExpiredCode() throws Exception {
        String code = registerAndGetCode(EMAIL);
        redis.delete(CODE_PREFIX + EMAIL);

        MvcResult existing = verifyEmail(EMAIL, code);
        MvcResult unknown = verifyEmail("khong.ton.tai@gmail.com", code);

        assertThat(statusOf(unknown)).isEqualTo(statusOf(existing));
        assertThat(jsonString(unknown, "$.message")).isEqualTo(jsonString(existing, "$.message"));
    }

    @Test
    @DisplayName("Mã của email A không xác thực được email B")
    void verify_codeOfAnotherEmail_isRejected() throws Exception {
        String codeA = registerAndGetCode(EMAIL);
        createUser("khac@gmail.com", PASSWORD, Role.USER, 1, 0);

        MvcResult result = verifyEmail("khac@gmail.com", codeA);

        assertThat(statusOf(result)).isEqualTo(400);
        assertThat(isVerified("khac@gmail.com")).isZero();
        assertThat(isVerified(EMAIL)).isZero();
    }

    // ======================= gửi lại mã =======================

    @Test
    @DisplayName("Gửi lại mã làm mã cũ mất hiệu lực, mã mới dùng được")
    void resend_invalidatesPreviousCode() throws Exception {
        String oldCode = registerAndGetCode(EMAIL);
        assertThat(statusOf(resend(EMAIL))).isEqualTo(200);
        String newCode = redis.opsForValue().get(CODE_PREFIX + EMAIL);
        org.junit.jupiter.api.Assumptions.assumeTrue(!oldCode.equals(newCode), "hai mã ngẫu nhiên trùng nhau");

        assertThat(statusOf(verifyEmail(EMAIL, oldCode))).isEqualTo(400);
        assertThat(statusOf(verifyEmail(EMAIL, newCode))).isEqualTo(200);
    }

    @Test
    @DisplayName("Gửi lại vượt giới hạn => 429 và không gửi thêm mail")
    void resend_overLimit_returns429_andSendsNoMoreMail() throws Exception {
        registerAndGetCode(EMAIL); // đăng ký đã tốn 1 lượt
        for (int i = 0; i < maxResendAttempts - 1; i++) {
            MvcResult ok = resend(EMAIL);
            assertThat(statusOf(ok)).as("lần gửi lại " + i + ": " + body(ok)).isEqualTo(200);
        }

        MvcResult over = resend(EMAIL);

        assertThat(statusOf(over)).as(body(over)).isEqualTo(429);
        verify(emailService, after(500).times(maxResendAttempts)).sendVerificationCode(eq(EMAIL), anyString());
        assertThat(redis.getExpire(RESEND_PREFIX + EMAIL, TimeUnit.SECONDS)).isBetween(1L, 3600L);
    }

    @Test
    @DisplayName("Mail lỗi => hoàn lượt gửi lại, nhưng tối đa 3 lần hoàn")
    void resend_mailFailure_refundsAtMostThreeTimes() throws Exception {
        doThrow(new RuntimeException("smtp down")).when(emailService).sendVerificationCode(anyString(), anyString());

        assertThat(statusOf(register(EMAIL))).isEqualTo(200);
        awaitValue(CODE_PREFIX + EMAIL);
        for (int i = 0; i < 3; i++) {
            assertThat(statusOf(resend(EMAIL))).as("lần gửi lại " + i).isEqualTo(200);
        }

        // 4 yêu cầu (1 đăng ký + 3 gửi lại), chỉ 3 lần đầu được hoàn
        awaitEquals(REFUND_PREFIX + EMAIL, "4");
        awaitEquals(RESEND_PREFIX + EMAIL, "1");
        Thread.sleep(300);
        assertThat(redis.opsForValue().get(RESEND_PREFIX + EMAIL)).isEqualTo("1");
    }

    @Test
    @DisplayName("resend-verification: email không tồn tại, đã xác thực, bị khoá cho phản hồi GIỐNG HỆT nhau và không gửi mail")
    void resend_nonEligibleAccounts_lookIdentical_andSendNoMail() throws Exception {
        createUser("da-xac-thuc@gmail.com", PASSWORD, Role.USER, 1, 1);
        createUser("bi-khoa@gmail.com", PASSWORD, Role.USER, 0, 0);

        MvcResult unknown = resend("khong.ton.tai@gmail.com");
        MvcResult verified = resend("da-xac-thuc@gmail.com");
        MvcResult locked = resend("bi-khoa@gmail.com");

        assertThat(statusOf(unknown)).as(body(unknown)).isEqualTo(400);
        assertThat(statusOf(verified)).isEqualTo(statusOf(unknown));
        assertThat(statusOf(locked)).isEqualTo(statusOf(unknown));
        assertThat(jsonString(verified, "$.message")).isEqualTo(jsonString(unknown, "$.message"));
        assertThat(jsonString(locked, "$.message")).isEqualTo(jsonString(unknown, "$.message"));
        verify(emailService, after(300).never()).sendVerificationCode(anyString(), anyString());
        assertThat(redis.keys("email_verify:*")).isEmpty();
    }

    // ======================= body sai cấu trúc =======================

    @Test
    @DisplayName("Body sai cấu trúc ở verify-email và resend-verification => lỗi 4xx, không bao giờ 500")
    void malformedBodies_neverCause5xx() throws Exception {
        List<String> bodies = List.of("{}", "[]", "\"x\"", "", "{",
                "{\"email\":null,\"code\":null}",
                "{\"email\":{\"a\":1},\"code\":\"123456\"}",
                "{\"email\":\"an@gmail.com\",\"code\":[\"1\"]}");

        for (String json : bodies) {
            for (String url : List.of(VERIFY_URL, RESEND_URL)) {
                MvcResult result = postJson(url, json);
                assertThat(statusOf(result)).as(url + " với body [" + json + "]: " + body(result))
                        .isBetween(400, 499);
                assertThat(body(result)).doesNotContain("Exception").doesNotContain("at com.");
            }
        }
        assertThat(redis.keys("email_verify:*:null")).isEmpty();
    }

    // ======================= race =======================

    @RepeatedTest(5)
    @DisplayName("Race: 8 request cùng verify với mã đúng => không bao giờ 5xx, giới hạn số lần thử vẫn được giữ, user được xác thực")
    void verify_concurrentCorrectCode_neverCauses5xx_andUserEndsVerified() throws Exception {
        String code = registerAndGetCode(EMAIL);

        List<Integer> statuses = runConcurrently(8, () -> statusOf(verifyEmail(EMAIL, code)));

        assertThat(statuses).allMatch(s -> s == 200 || s == 400 || s == 429);
        assertThat(statuses.stream().filter(s -> s == 200).count()).isGreaterThanOrEqualTo(1);
        assertThat(statuses.stream().filter(s -> s == 200).count())
                .as("chỉ tối đa maxAttempts request được đánh giá").isLessThanOrEqualTo(maxAttempts);
        assertThat(isVerified(EMAIL)).isEqualTo(1);
    }

    @RepeatedTest(5)
    @DisplayName("[GIẢ THUYẾT] Race: 5 request cùng verify với mã đúng => mã dùng một lần, đúng 1 request thành công")
    void verify_concurrentCorrectCode_codeIsSingleUse() throws Exception {
        String code = registerAndGetCode(EMAIL);

        List<Integer> statuses = runConcurrently(5, () -> statusOf(verifyEmail(EMAIL, code)));

        assertThat(statuses.stream().filter(s -> s == 200).count())
                .as("code đang được đọc rồi mới xoá (không atomic) nên nhiều request cùng thành công: " + statuses)
                .isEqualTo(1);
    }

    private record Outcome(String action, int status) {
    }

    @RepeatedTest(10)
    @DisplayName("Race: verify chạy đua với gửi lại mã => không 5xx, phản hồi verify khớp trạng thái thật của user")
    void verify_vs_resend_race_keepsStateCoherent() throws Exception {
        String code = registerAndGetCode(EMAIL);
        AtomicInteger turn = new AtomicInteger();

        List<Outcome> outcomes = runConcurrently(2, () -> {
            if (turn.getAndIncrement() == 0) {
                return new Outcome("verify", statusOf(verifyEmail(EMAIL, code)));
            }
            return new Outcome("resend", statusOf(resend(EMAIL)));
        });

        int verifyStatus = outcomes.stream().filter(o -> o.action().equals("verify")).findFirst().orElseThrow()
                .status();
        int resendStatus = outcomes.stream().filter(o -> o.action().equals("resend")).findFirst().orElseThrow()
                .status();
        assertThat(verifyStatus).isIn(200, 400, 429);
        assertThat(resendStatus).isIn(200, 400);
        assertThat(isVerified(EMAIL) == 1).as("verify trả " + verifyStatus).isEqualTo(verifyStatus == 200);
    }

    @Test
    @DisplayName("Không có mail nào bị gửi khi xác thực sai (không rò mã qua mail)")
    void verify_wrongCode_sendsNoExtraMail() throws Exception {
        String code = registerAndGetCode(EMAIL);
        verify(emailService, timeout(5000).times(1)).sendVerificationCode(eq(EMAIL), anyString());

        verifyEmail(EMAIL, wrongCodeFor(code));

        verify(emailService, after(300).times(1)).sendVerificationCode(anyString(), anyString());
        verify(emailService, never()).sendPasswordResetOtp(anyString(), anyString(),
                org.mockito.ArgumentMatchers.anyInt());
    }
}
