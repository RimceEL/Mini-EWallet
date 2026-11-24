package com.backend.backend.auth.security.flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import org.apache.commons.codec.digest.DigestUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MvcResult;

import com.backend.backend.auth.model.Role;
import com.backend.backend.auth.security.SecurityIntegrationTestBase;

public class PasswordResetFlowTest extends SecurityIntegrationTestBase {

    private static final String EMAIL = "an@gmail.com";
    private static final String ATTACKER = "attacker@gmail.com";
    private static final String NEW_PASSWORD = "MatKhauMoiDuDai!2024";
    private static final String WRONG_PASSWORD = "SaiMatKhau!12345";

    private static final String OTP_PREFIX = "pwreset:otp:";
    private static final String OTP_FAIL_PREFIX = "pwreset:otp-fail:";
    private static final String TOKEN_PREFIX = "pwreset:token:";
    private static final String RESEND_PREFIX = "pwreset:resend:";
    private static final String REFUND_PREFIX = "pwreset:refund:";

    @BeforeEach
    void seedUser() {
        createUser(EMAIL, PASSWORD, Role.USER, 1, 1);
    }

    // ======================= helpers =======================

    private MvcResult requestOtpRaw(String email) throws Exception {
        return postJson("/api/v1/auth/otp-reset-request", "{\"email\":\"" + email + "\"}");
    }

    private String requestOtp(String email) throws Exception {
        clearInvocations(emailService);
        MvcResult result = requestOtpRaw(email);
        assertThat(statusOf(result)).as(body(result)).isEqualTo(200);
        ArgumentCaptor<String> otp = ArgumentCaptor.forClass(String.class);
        verify(emailService, timeout(5000)).sendPasswordResetOtp(eq(email), otp.capture(), anyInt());
        return otp.getValue();
    }

    private MvcResult verifyOtpRaw(String email, String code) throws Exception {
        return postJson("/api/v1/auth/otp-reset-verify",
                "{\"email\":\"" + email + "\",\"code\":\"" + code + "\"}");
    }

    private String exchangeOtp(String email, String otp) throws Exception {
        MvcResult result = verifyOtpRaw(email, otp);
        assertThat(statusOf(result)).as(body(result)).isEqualTo(200);
        return jsonString(result, "$.data.token");
    }

    private String obtainToken(String email) throws Exception {
        return exchangeOtp(email, requestOtp(email));
    }

    private MvcResult resetRaw(String email, String token, String newPassword) throws Exception {
        return postJson("/api/v1/auth/otp-reset-password",
                "{\"email\":\"" + email + "\",\"token\":\"" + token + "\",\"newPassword\":\"" + newPassword + "\"}");
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

    private String wrongOtpFor(String otp) {
        return "00000000".equals(otp) ? "11111111" : "00000000";
    }

    // ======================= luồng đầy đủ =======================

    @Test
    @DisplayName("Luồng đầy đủ: mật khẩu mới dùng được, mật khẩu cũ hết hiệu lực, hash BCrypt, key pwreset:* được dọn, mail báo đổi mật khẩu được gửi")
    void fullFlow_changesPasswordAndCleansUp() throws Exception {
        String otp = requestOtp(EMAIL);
        String token = exchangeOtp(EMAIL, otp);

        MvcResult result = resetRaw(EMAIL, token, NEW_PASSWORD);

        assertThat(statusOf(result)).as(body(result)).isEqualTo(200);
        verify(emailService, timeout(5000)).sendPasswordChangedNotice(EMAIL);
        String stored = jdbc.queryForObject("SELECT password FROM users WHERE email = ?", String.class, EMAIL);
        assertThat(stored).startsWith("$2").isNotEqualTo(NEW_PASSWORD);
        assertThat(redis.hasKey(OTP_PREFIX + EMAIL)).isFalse();
        assertThat(redis.hasKey(OTP_FAIL_PREFIX + EMAIL)).isFalse();
        assertThat(redis.hasKey(TOKEN_PREFIX + EMAIL)).isFalse();
        assertThat(redis.hasKey(RESEND_PREFIX + EMAIL)).isFalse();
        assertThat(statusOf(loginRaw(EMAIL, PASSWORD))).isEqualTo(401);
        assertThat(statusOf(loginRaw(EMAIL, NEW_PASSWORD))).isEqualTo(200);
    }

    @Test
    @DisplayName("Đặt lại mật khẩu thu hồi MỌI phiên đăng nhập cũ, nhưng không ảnh hưởng phiên của user khác")
    void reset_revokesAllSessionsOfThatUserOnly() throws Exception {
        createUser("khac@gmail.com", PASSWORD, Role.USER, 1, 1);
        Tokens other = loginOk("khac@gmail.com", PASSWORD);
        Tokens old = loginOk(EMAIL, PASSWORD);

        assertThat(statusOf(resetRaw(EMAIL, obtainToken(EMAIL), NEW_PASSWORD))).isEqualTo(200);

        assertThat(sessionKeys(EMAIL)).isEmpty();
        MvcResult oldAccess = getWithToken(USER_URL, old.access());
        assertThat(statusOf(oldAccess)).isEqualTo(401);
        assertThat(jsonString(oldAccess, "$.code")).isEqualTo("TOKEN_REVOKED");
        assertThat(statusOf(refreshRaw(old.refresh()))).isEqualTo(401);
        assertThat(statusOf(getWithToken(USER_URL, other.access()))).isEqualTo(200);
    }

    @Test
    @DisplayName("Đặt lại mật khẩu gỡ khoá đăng nhập do sai nhiều lần")
    void reset_clearsLoginLock() throws Exception {
        for (int i = 0; i < LOCK_ATTEMPTS_1; i++) {
            assertThat(statusOf(loginRaw(EMAIL, WRONG_PASSWORD))).isEqualTo(401);
        }
        assertThat(statusOf(loginRaw(EMAIL, PASSWORD))).as("đang bị khoá").isEqualTo(429);

        assertThat(statusOf(resetRaw(EMAIL, obtainToken(EMAIL), NEW_PASSWORD))).isEqualTo(200);

        assertThat(statusOf(loginRaw(EMAIL, NEW_PASSWORD))).isEqualTo(200);
    }

    // ======================= xin OTP =======================

    @Test
    @DisplayName("Email không tồn tại: phản hồi GIỐNG HỆT email thật, không gửi mail, không tạo OTP, nhưng vẫn bị tính rate limit")
    void requestOtp_unknownEmail_looksIdentical_butIsRateLimited() throws Exception {
        String unknown = "khong.ton.tai@gmail.com";

        MvcResult existing = requestOtpRaw(EMAIL);
        MvcResult missing = requestOtpRaw(unknown);

        assertThat(statusOf(missing)).as(body(missing)).isEqualTo(200);
        assertThat(statusOf(missing)).isEqualTo(statusOf(existing));
        assertThat(jsonString(missing, "$.message")).isEqualTo(jsonString(existing, "$.message"));
        verify(emailService, after(400).never()).sendPasswordResetOtp(eq(unknown), anyString(), anyInt());
        assertThat(redis.hasKey(OTP_PREFIX + unknown)).isFalse();
        assertThat(redis.opsForValue().get(RESEND_PREFIX + unknown)).isEqualTo("1");

        for (int i = 0; i < 4; i++) {
            assertThat(statusOf(requestOtpRaw(unknown))).isEqualTo(200);
        }
        MvcResult over = requestOtpRaw(unknown);
        assertThat(statusOf(over)).as("email lạ cũng bị 429 y như email thật").isEqualTo(429);
    }

    @Test
    @DisplayName("Xin OTP vượt giới hạn => 429, không gửi thêm mail")
    void requestOtp_overLimit_returns429_andSendsNoMoreMail() throws Exception {
        for (int i = 0; i < 5; i++) {
            MvcResult ok = requestOtpRaw(EMAIL);
            assertThat(statusOf(ok)).as("lần " + i + ": " + body(ok)).isEqualTo(200);
        }

        MvcResult over = requestOtpRaw(EMAIL);

        assertThat(statusOf(over)).as(body(over)).isEqualTo(429);
        verify(emailService, after(500).times(5)).sendPasswordResetOtp(eq(EMAIL), anyString(), anyInt());
        assertThat(redis.getExpire(RESEND_PREFIX + EMAIL, TimeUnit.SECONDS)).isBetween(1L, 24 * 3600L);
    }

    @Test
    @DisplayName("Mail lỗi => hoàn lượt xin OTP, nhưng tối đa 3 lần hoàn")
    void requestOtp_mailFailure_refundsAtMostThreeTimes() throws Exception {
        doThrow(new RuntimeException("smtp down")).when(emailService)
                .sendPasswordResetOtp(anyString(), anyString(), anyInt());

        for (int i = 0; i < 5; i++) {
            assertThat(statusOf(requestOtpRaw(EMAIL))).as("lần " + i).isEqualTo(200);
        }

        awaitEquals(REFUND_PREFIX + EMAIL, "5");
        awaitEquals(RESEND_PREFIX + EMAIL, "2");
        Thread.sleep(300);
        assertThat(redis.opsForValue().get(RESEND_PREFIX + EMAIL)).isEqualTo("2");
    }

    @Test
    @DisplayName("Xin OTP mới làm OTP cũ mất hiệu lực và reset bộ đếm sai")
    void requestOtp_again_invalidatesOldOtp_andResetsFailCounter() throws Exception {
        String oldOtp = requestOtp(EMAIL);
        assertThat(statusOf(verifyOtpRaw(EMAIL, wrongOtpFor(oldOtp)))).isEqualTo(400);
        assertThat(redis.hasKey(OTP_FAIL_PREFIX + EMAIL)).isTrue();

        String newOtp = requestOtp(EMAIL);

        assertThat(redis.hasKey(OTP_FAIL_PREFIX + EMAIL)).isFalse();
        org.junit.jupiter.api.Assumptions.assumeTrue(!oldOtp.equals(newOtp), "hai OTP ngẫu nhiên trùng nhau");
        assertThat(statusOf(verifyOtpRaw(EMAIL, oldOtp))).isEqualTo(400);
        assertThat(statusOf(verifyOtpRaw(EMAIL, newOtp))).isEqualTo(200);
    }

    // ======================= đổi OTP lấy token =======================

    @Test
    @DisplayName("Sai OTP quá ngưỡng => 400 từng lần, lần kế (dù đúng) 429 và OTP bị xoá; xin OTP mới thì làm lại được")
    void verifyOtp_tooManyWrong_locksOut_andNewOtpRecovers() throws Exception {
        String otp = requestOtp(EMAIL);

        for (int i = 0; i < 5; i++) {
            assertThat(statusOf(verifyOtpRaw(EMAIL, wrongOtpFor(otp)))).as("lần sai " + i).isEqualTo(400);
        }
        MvcResult locked = verifyOtpRaw(EMAIL, otp);
        assertThat(statusOf(locked)).as(body(locked)).isEqualTo(429);
        assertThat(redis.hasKey(OTP_PREFIX + EMAIL)).isFalse();
        assertThat(redis.hasKey(TOKEN_PREFIX + EMAIL)).isFalse();

        String newOtp = requestOtp(EMAIL);
        assertThat(statusOf(verifyOtpRaw(EMAIL, newOtp))).isEqualTo(200);
    }

    @Test
    @DisplayName("Nhập đúng OTP ở lượt cuối còn được phép (lượt thứ 5) vẫn thành công")
    void verifyOtp_correctOnLastAllowedAttempt_succeeds() throws Exception {
        String otp = requestOtp(EMAIL);
        for (int i = 0; i < 4; i++) {
            assertThat(statusOf(verifyOtpRaw(EMAIL, wrongOtpFor(otp)))).isEqualTo(400);
        }

        assertThat(statusOf(verifyOtpRaw(EMAIL, otp))).isEqualTo(200);
    }

    @Test
    @DisplayName("OTP chỉ dùng được một lần; OTP hết hạn (mất key) => 400")
    void verifyOtp_isSingleUse_andExpiredIsRejected() throws Exception {
        String otp = requestOtp(EMAIL);
        assertThat(statusOf(verifyOtpRaw(EMAIL, otp))).isEqualTo(200);
        assertThat(statusOf(verifyOtpRaw(EMAIL, otp))).as("dùng lại OTP").isEqualTo(400);

        String otp2 = requestOtp(EMAIL);
        redis.delete(OTP_PREFIX + EMAIL);
        assertThat(statusOf(verifyOtpRaw(EMAIL, otp2))).as("OTP hết hạn").isEqualTo(400);
    }

    @Test
    @DisplayName("Redis chỉ lưu BĂM của OTP và token (có TTL), không lưu giá trị thô ở bất kỳ key nào")
    void redis_storesOnlyHashes() throws Exception {
        String otp = requestOtp(EMAIL);
        String storedOtp = redis.opsForValue().get(OTP_PREFIX + EMAIL);
        assertThat(storedOtp).isNotEqualTo(otp).matches("^[0-9a-f]{64}$");
        assertThat(redis.getExpire(OTP_PREFIX + EMAIL, TimeUnit.SECONDS)).isBetween(1L, 600L);

        String token = exchangeOtp(EMAIL, otp);
        String storedToken = redis.opsForValue().get(TOKEN_PREFIX + EMAIL);
        assertThat(storedToken).isNotEqualTo(token).isEqualTo(DigestUtils.sha256Hex(token));
        assertThat(redis.getExpire(TOKEN_PREFIX + EMAIL, TimeUnit.SECONDS)).isBetween(1L, 600L);

        Set<String> keys = redis.keys("*");
        assertThat(keys).noneMatch(k -> k.contains(token) || k.contains(otp));
        for (String key : keys) {
            String value = redis.opsForValue().get(key);
            assertThat(value).isNotEqualTo(token).isNotEqualTo(otp);
        }
    }

    // ======================= token gắn với email =======================

    @Test
    @DisplayName("OTP và token của kẻ tấn công không dùng được cho email nạn nhân, và không bị đốt")
    void secrets_areBoundToEmail() throws Exception {
        createUser(ATTACKER, PASSWORD, Role.USER, 1, 1);
        String attackerOtp = requestOtp(ATTACKER);

        MvcResult otpOnVictim = verifyOtpRaw(EMAIL, attackerOtp);
        assertThat(statusOf(otpOnVictim)).isEqualTo(400);
        assertThat(redis.hasKey(TOKEN_PREFIX + EMAIL)).isFalse();

        String attackerToken = exchangeOtp(ATTACKER, attackerOtp);
        MvcResult tokenOnVictim = resetRaw(EMAIL, attackerToken, NEW_PASSWORD);

        assertThat(statusOf(tokenOnVictim)).as(body(tokenOnVictim)).isEqualTo(400);
        assertThat(statusOf(loginRaw(EMAIL, PASSWORD))).as("mật khẩu nạn nhân không đổi").isEqualTo(200);
        assertThat(redis.hasKey(TOKEN_PREFIX + ATTACKER)).as("token thật của kẻ tấn công không bị đốt").isTrue();
        assertThat(statusOf(resetRaw(ATTACKER, attackerToken, NEW_PASSWORD))).isEqualTo(200);
    }

    @Test
    @DisplayName("Token hết hạn (mất key) hoặc sai => 400, mật khẩu không đổi")
    void resetPassword_expiredOrWrongToken_isRejected() throws Exception {
        String token = obtainToken(EMAIL);

        assertThat(statusOf(resetRaw(EMAIL, "token-sai", NEW_PASSWORD))).isEqualTo(400);
        assertThat(redis.hasKey(TOKEN_PREFIX + EMAIL)).as("token sai không làm mất token thật").isTrue();

        redis.delete(TOKEN_PREFIX + EMAIL);
        assertThat(statusOf(resetRaw(EMAIL, token, NEW_PASSWORD))).isEqualTo(400);
        assertThat(statusOf(loginRaw(EMAIL, PASSWORD))).isEqualTo(200);
    }

    @Test
    @DisplayName("Token chỉ dùng được một lần")
    void resetPassword_tokenIsSingleUse() throws Exception {
        String token = obtainToken(EMAIL);
        assertThat(statusOf(resetRaw(EMAIL, token, NEW_PASSWORD))).isEqualTo(200);

        MvcResult replay = resetRaw(EMAIL, token, "MotMatKhauKhac!2025x");

        assertThat(statusOf(replay)).as(body(replay)).isEqualTo(400);
        assertThat(statusOf(loginRaw(EMAIL, NEW_PASSWORD))).isEqualTo(200);
    }

    // ======================= race =======================

    @RepeatedTest(5)
    @DisplayName("Race: 5 request cùng đổi OTP đúng lấy token => đúng 1 token, 4 request còn lại 400, token dùng được")
    void verifyOtp_concurrent_exactlyOneToken() throws Exception {
        String otp = requestOtp(EMAIL);

        List<MvcResult> results = runConcurrently(5, () -> verifyOtpRaw(EMAIL, otp));

        List<MvcResult> winners = results.stream().filter(r -> statusOf(r) == 200).toList();
        assertThat(winners).hasSize(1);
        assertThat(results).filteredOn(r -> statusOf(r) != 200).allMatch(r -> statusOf(r) == 400);
        String token = jsonString(winners.get(0), "$.data.token");
        assertThat(statusOf(resetRaw(EMAIL, token, NEW_PASSWORD))).isEqualTo(200);
    }

    private record Outcome(int index, int status) {
    }

    @RepeatedTest(5)
    @DisplayName("Race: 8 request cùng đặt lại mật khẩu bằng MỘT token => đúng 1 thành công, mật khẩu trong DB là của request thắng")
    void resetPassword_concurrent_exactlyOneWinner() throws Exception {
        String token = obtainToken(EMAIL);
        List<String> passwords = IntStream.range(0, 8)
                .mapToObj(i -> "MatKhauMoi" + i + "DuDai!2024").toList();
        AtomicInteger ticket = new AtomicInteger();

        List<Outcome> outcomes = runConcurrently(8, () -> {
            int i = ticket.getAndIncrement();
            return new Outcome(i, statusOf(resetRaw(EMAIL, token, passwords.get(i))));
        });

        List<Outcome> winners = outcomes.stream().filter(o -> o.status() == 200).toList();
        List<Outcome> losers = outcomes.stream().filter(o -> o.status() != 200).toList();
        assertThat(winners).hasSize(1);
        assertThat(losers).hasSize(7).allMatch(o -> o.status() == 400);

        assertThat(statusOf(loginRaw(EMAIL, passwords.get(winners.get(0).index())))).isEqualTo(200);
        assertThat(statusOf(loginRaw(EMAIL, passwords.get(losers.get(0).index())))).isEqualTo(401);
        assertThat(statusOf(loginRaw(EMAIL, PASSWORD))).isEqualTo(401);
    }
}