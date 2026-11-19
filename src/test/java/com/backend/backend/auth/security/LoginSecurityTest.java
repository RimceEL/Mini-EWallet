package com.backend.backend.auth.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MvcResult;

import com.backend.backend.auth.model.Role;
import com.jayway.jsonpath.JsonPath;

public class LoginSecurityTest extends SecurityIntegrationTestBase {

    private static final String EMAIL = "an@gmail.com";
    private static final String WRONG = "SaiMatKhau!12345";
    private static final String FAIL_PREFIX = "login:fail:";
    private static final String LOCK_PREFIX = "login:lock:";
    private static final String GENERIC_BAD_CREDENTIALS = "Email hoặc mật khẩu không chính xác";

    private static final String LOCK_KEY = "login:lock:" + EMAIL;
    private static final String WRONG_PASSWORD = "SaiMatKhau!123456";

    @BeforeEach
    void seedUser() {
        createUser(EMAIL, PASSWORD, Role.USER, 1, 1);
    }

    private MvcResult wrong(String email) throws Exception {
        return loginRaw(email, WRONG);
    }

    private void failTimes(String email, int times) throws Exception {
        for (int i = 0; i < times; i++) {
            assertThat(statusOf(wrong(email))).as("lần sai thứ " + (i + 1)).isEqualTo(401);
        }
    }

    @Test
    @DisplayName("Đăng nhập đúng: chỉ trả 2 token, token không chứa mật khẩu hay dữ liệu thừa")
    void correctLogin_issuesTokens_andLeaksNoSensitiveData() throws Exception {
        MvcResult result = loginRaw(EMAIL, PASSWORD);

        assertThat(statusOf(result)).isEqualTo(200);
        Map<String, Object> data = JsonPath.read(body(result), "$.data");
        assertThat(data.keySet()).containsExactlyInAnyOrder("accessToken", "refreshToken");
        assertThat(body(result)).doesNotContain("$2a$").doesNotContain("password");

        Tokens tokens = parseTokens(result);
        Map<String, Object> accessClaims = JsonPath.read(payloadOf(tokens.access()), "$");
        Map<String, Object> refreshClaims = JsonPath.read(payloadOf(tokens.refresh()), "$");
        assertThat(accessClaims.keySet()).containsExactlyInAnyOrder("roles", "jti", "sub", "iat", "exp");
        assertThat(refreshClaims.keySet()).containsExactlyInAnyOrder("jti", "sub", "iat", "exp");
        assertThat(payloadOf(tokens.access())).doesNotContain("$2a$").doesNotContain("password");
        assertThat(accessClaims.get("jti")).isEqualTo(refreshClaims.get("jti"));
    }

    @Test
    @DisplayName("Sai mật khẩu: 401 với thông báo chung, không lộ chi tiết nội bộ")
    void wrongPassword_returnsGenericError_withoutInternalDetails() throws Exception {
        MvcResult result = wrong(EMAIL);

        assertThat(statusOf(result)).isEqualTo(401);
        assertThat(jsonString(result, "$.message")).isEqualTo(GENERIC_BAD_CREDENTIALS);
        assertThat(body(result)).doesNotContain("Exception").doesNotContain("org.springframework")
                .doesNotContain("at com.").doesNotContain("SELECT").doesNotContain("stackTrace");
    }

    @Test
    @DisplayName("Email không tồn tại cho phản hồi GIỐNG HỆT sai mật khẩu (chống dò email)")
    void unknownEmail_looksIdenticalToWrongPassword() throws Exception {
        MvcResult existing = wrong(EMAIL);
        MvcResult unknown = wrong("khong.ton.tai@gmail.com");

        assertThat(statusOf(unknown)).isEqualTo(statusOf(existing));
        assertThat(jsonString(unknown, "$.message")).isEqualTo(jsonString(existing, "$.message"));
    }

    @ParameterizedTest(name = "is_active={0}, is_verified={1}")
    @CsvSource({ "0,1", "1,0" })
    @DisplayName("[GIẢ THUYẾT] Sai mật khẩu với tài khoản bị khoá / chưa xác thực phải GIỐNG HỆT tài khoản bình thường")
    void wrongPassword_forNonActiveAccount_looksSameAsNormalAccount(int isActive, int isVerified)
            throws Exception {
        createUser("state@gmail.com", PASSWORD, Role.USER, isActive, isVerified);

        MvcResult normal = wrong(EMAIL);
        MvcResult variant = wrong("state@gmail.com");

        assertThat(statusOf(variant)).isEqualTo(statusOf(normal));
        assertThat(jsonString(variant, "$.message"))
                .as("thông báo lộ trạng thái tài khoản khi chưa biết mật khẩu")
                .isEqualTo(jsonString(normal, "$.message"));
    }

    @ParameterizedTest(name = "is_active={0}, is_verified={1}")
    @CsvSource({ "0,1", "1,0" })
    @DisplayName("Mật khẩu ĐÚNG nhưng tài khoản bị khoá / chưa xác thực => không cấp token, không tạo phiên")
    void correctPassword_forNonActiveAccount_issuesNothing(int isActive, int isVerified) throws Exception {
        createUser("state@gmail.com", PASSWORD, Role.USER, isActive, isVerified);

        MvcResult result = loginRaw("state@gmail.com", PASSWORD);

        assertThat(statusOf(result)).isBetween(400, 499);
        assertThat(body(result)).doesNotContain("accessToken").doesNotContain("refreshToken");
        assertThat(sessionKeys("state@gmail.com")).isEmpty();
    }

    @ParameterizedTest(name = "body = {0}")
    @ValueSource(strings = {
            "{}",
            "{\"email\":\"an@gmail.com\"}",
            "{\"email\":\"an@gmail.com\",\"password\":null}",
            "{\"email\":null,\"password\":\"x\"}",
            "{\"email\":\"\",\"password\":\"\"}",
            "{\"email\":{\"a\":1},\"password\":\"x\"}",
            "{\"email\":\"an@gmail.com\",\"password\":[\"x\"]}",
            "[]",
            "\"x\"",
            "" })
    @DisplayName("Body đăng nhập sai cấu trúc hoặc thiếu trường => lỗi 4xx, không bao giờ 500, không lộ chi tiết")
    void malformedLoginBody_neverCauses500(String json) throws Exception {
        MvcResult result = postJson("/api/v1/auth/login", json);

        assertThat(statusOf(result)).isBetween(400, 499);
        assertThat(body(result)).doesNotContain("accessToken").doesNotContain("Exception")
                .doesNotContain("at com.");
    }

    @Test
    @DisplayName("[GIẢ THUYẾT] Mật khẩu dài 100.000 ký tự => bị từ chối nhanh bằng lỗi 4xx, không 500")
    void hugePassword_isRejectedQuicklyAndCleanly() throws Exception {
        long start = System.nanoTime();

        MvcResult result = loginRaw(EMAIL, "a".repeat(100_000));

        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertThat(statusOf(result)).as("mật khẩu quá dài gây lỗi: " + body(result)).isBetween(400, 499);
        assertThat(elapsedMs).as("xử lý mật khẩu khổng lồ không được chậm").isLessThan(5_000);
    }

    @Test
    @DisplayName("Email viết hoa và có khoảng trắng thừa vẫn đăng nhập được (chuẩn hoá email)")
    void emailCaseAndWhitespace_areNormalized() throws Exception {
        assertThat(statusOf(loginRaw("  AN@GMAIL.COM  ", PASSWORD))).isEqualTo(200);
    }

    @Test
    @DisplayName("Sai đủ số lần => bị khoá, kể cả khi nhập ĐÚNG mật khẩu; không tạo phiên")
    void locksAfterMaxAttempts_evenForCorrectPassword() throws Exception {
        failTimes(EMAIL, LOCK_ATTEMPTS_1);

        assertThat(statusOf(wrong(EMAIL))).isEqualTo(429);
        MvcResult correct = loginRaw(EMAIL, PASSWORD);
        assertThat(statusOf(correct)).isEqualTo(429);
        assertThat(body(correct)).doesNotContain("accessToken");
        assertThat(sessionKeys(EMAIL)).isEmpty();
        assertThat(redis.getExpire(LOCK_PREFIX + EMAIL, TimeUnit.SECONDS)).isBetween(1L, LOCK_MINUTES_1 * 60L);
    }

    @Test
    @DisplayName("Chưa đủ số lần sai thì không bị khoá; đăng nhập đúng xoá bộ đếm")
    void belowThreshold_notLocked_andSuccessResetsCounter() throws Exception {
        failTimes(EMAIL, LOCK_ATTEMPTS_1 - 1);
        assertThat(statusOf(loginRaw(EMAIL, PASSWORD))).isEqualTo(200);
        assertThat(redis.hasKey(FAIL_PREFIX + EMAIL)).isFalse();

        failTimes(EMAIL, LOCK_ATTEMPTS_1 - 1);

        assertThat(statusOf(loginRaw(EMAIL, PASSWORD))).isEqualTo(200);
    }

    @Test
    @DisplayName("Hết thời gian khoá thì đăng nhập đúng lại được")
    void lockExpires_thenCorrectLoginWorks() throws Exception {
        failTimes(EMAIL, LOCK_ATTEMPTS_1);
        assertThat(statusOf(loginRaw(EMAIL, PASSWORD))).isEqualTo(429);

        redis.expire(LOCK_PREFIX + EMAIL, Duration.ofMillis(300));
        Thread.sleep(900);

        assertThat(statusOf(loginRaw(EMAIL, PASSWORD))).isEqualTo(200);
    }

    private void failUntilLocked(String email, int allowed) throws Exception {
        failTimes(email, allowed);
        assertThat(statusOf(wrong(email))).as("lần chạm ngưỡng phải bị khoá").isEqualTo(429);
    }

    @Test
    @DisplayName("Khoá leo thang: lần khoá sau lâu hơn lần trước (1 phút, 5 phút, 15 phút)")
    void lockDurations_escalate() throws Exception {
        String lockKey = LOCK_PREFIX + EMAIL;

        failUntilLocked(EMAIL, LOCK_ATTEMPTS_1);
        assertThat(redis.getExpire(lockKey, TimeUnit.SECONDS)).isBetween(1L, LOCK_MINUTES_1 * 60L);

        redis.delete(lockKey);
        failUntilLocked(EMAIL, LOCK_ATTEMPTS_2 - LOCK_ATTEMPTS_1 - 1);
        assertThat(redis.getExpire(lockKey, TimeUnit.SECONDS))
                .isBetween(LOCK_MINUTES_1 * 60L + 1, LOCK_MINUTES_2 * 60L);

        redis.delete(lockKey);
        failUntilLocked(EMAIL, LOCK_ATTEMPTS_3 - LOCK_ATTEMPTS_2 - 1);
        assertThat(redis.getExpire(lockKey, TimeUnit.SECONDS))
                .isBetween(LOCK_MINUTES_2 * 60L + 1, LOCK_MINUTES_3 * 60L);
    }

    @Test
    @DisplayName("Đổi hoa/thường hoặc thêm khoảng trắng trong email không né được bộ đếm")
    void emailVariants_shareOneCounter() throws Exception {
        String[] variants = { "AN@gmail.com", " an@gmail.com ", "An@GMAIL.com" };
        for (int i = 0; i < LOCK_ATTEMPTS_1; i++) {
            assertThat(statusOf(wrong(variants[i % variants.length]))).isEqualTo(401);
        }

        assertThat(statusOf(wrong(EMAIL))).isEqualTo(429);
    }

    @Test
    @DisplayName("Email không tồn tại cũng bị khoá y như email thật (không dò được email qua cơ chế khoá)")
    void unknownEmail_isLockedLikeExistingEmail() throws Exception {
        List<Integer> existing = new ArrayList<>();
        List<Integer> unknown = new ArrayList<>();
        for (int i = 0; i < LOCK_ATTEMPTS_1 + 2; i++) {
            existing.add(statusOf(wrong(EMAIL)));
            unknown.add(statusOf(wrong("khong.ton.tai@gmail.com")));
        }

        assertThat(unknown).isEqualTo(existing);
        assertThat(existing).containsExactly(401, 401, 401, 429, 429);
    }

    @Test
    @DisplayName("Khoá một tài khoản không ảnh hưởng tài khoản khác")
    void lockingOneAccount_doesNotAffectOthers() throws Exception {
        createUser("khac@gmail.com", PASSWORD, Role.USER, 1, 1);
        failTimes(EMAIL, LOCK_ATTEMPTS_1);
        assertThat(statusOf(wrong(EMAIL))).isEqualTo(429);

        assertThat(statusOf(loginRaw("khac@gmail.com", PASSWORD))).isEqualTo(200);
    }

    @Test
    @DisplayName("Bộ đếm lần sai có thời hạn (không sống vĩnh viễn)")
    void failureCounter_hasExpiry() throws Exception {
        wrong(EMAIL);

        assertThat(redis.getExpire(FAIL_PREFIX + EMAIL, TimeUnit.SECONDS)).isBetween(1L, 60L * 60);
    }

    @Test
    @DisplayName("[GIẢ THUYẾT] 40 lần đoán mật khẩu SONG SONG không được vượt giới hạn số lần thử")
    void concurrentBruteForce_cannotExceedAttemptLimit() throws Exception {
        String target = "muctieu@gmail.com";
        createUserWithHash(target, new BCryptPasswordEncoder(10).encode(PASSWORD), Role.USER, 1, 1);

        List<MvcResult> results = runConcurrently(40, () -> wrong(target));

        long guessesEvaluated = results.stream().filter(r -> statusOf(r) == 401).count();
        long blocked = results.stream().filter(r -> statusOf(r) == 429).count();
        assertThat(guessesEvaluated)
                .as("số lần đoán thực sự được xử lý = " + guessesEvaluated + ", bị chặn = " + blocked
                        + ", giới hạn cho phép = " + LOCK_ATTEMPTS_1)
                .isLessThanOrEqualTo(LOCK_ATTEMPTS_1);
    }

    private int wrongAttempt() throws Exception {
        return statusOf(loginRaw(EMAIL, WRONG_PASSWORD));
    }

    private int driveThroughAllThreeLockLevels() throws Exception {
        int locks = 0;
        for (int attempt = 1; attempt <= LOCK_ATTEMPTS_3 + 1; attempt++) {
            if (wrongAttempt() == 429) {
                locks++;
                redis.delete(LOCK_KEY);
            }
        }
        return locks;
    }

    @Test
    @DisplayName("[GIẢ THUYẾT] Sau khi khoá mức 3 hết hạn, tiếp tục sai mật khẩu thì phải bị khoá lại")
    void afterLevelThreeLockExpires_furtherWrongAttemptsMustLockAgain() throws Exception {
        assertThat(driveThroughAllThreeLockLevels())
                .as("tiền điều kiện: 3 mức khoá đầu tiên hoạt động (ở lần sai thứ 4, 7, 10)")
                .isEqualTo(3);

        int locksAfterLevelThree = 0;
        for (int i = 0; i < 20; i++) {
            if (wrongAttempt() == 429) {
                locksAfterLevelThree++;
                redis.delete(LOCK_KEY);
            }
        }

        assertThat(locksAfterLevelThree)
                .as("20 lần sai tiếp theo sau khoá mức 3 không bị khoá lần nào => đoán mật khẩu vô hạn")
                .isGreaterThan(0);
    }

    @Test
    @DisplayName("[GIẢ THUYẾT] 60 lần thử sai (khoá nào hết hạn là thử tiếp): số lần mật khẩu thật sự được kiểm tra phải bị chặn trần")
    void totalPasswordGuesses_areBoundedEvenWhenLocksExpire() throws Exception {
        int guessesActuallyChecked = 0;
        for (int i = 0; i < 60; i++) {
            int status = wrongAttempt();
            if (status == 429) {
                redis.delete(LOCK_KEY);
            } else if (status == 401) {
                guessesActuallyChecked++;
            }
        }

        assertThat(guessesActuallyChecked)
                .as("số lần mật khẩu được so khớp trong 60 lần thử; kẻ tấn công nên bị chặn gần mức "
                        + LOCK_ATTEMPTS_3)
                .isLessThanOrEqualTo(LOCK_ATTEMPTS_3 * 2);
    }

    private void driveToJustBeforeLevelThree() throws Exception {
        for (int attempt = 1; attempt <= LOCK_ATTEMPTS_3; attempt++) {
            if (wrongAttempt() == 429) {
                redis.delete(LOCK_KEY);
            }
        }
    }

    private void driveToLevelThreeLock() throws Exception {
        driveToJustBeforeLevelThree();
        assertThat(wrongAttempt())
                .as("lần sai thứ " + (LOCK_ATTEMPTS_3 + 1) + " phải kích hoạt khoá mức 3")
                .isEqualTo(429);
        assertThat(redis.hasKey(LOCK_KEY)).as("khoá mức 3 phải còn tồn tại").isTrue();
    }

    @Test
    @DisplayName("Khoá mức 3 kéo dài: nhập ĐÚNG mật khẩu vẫn bị chặn, không cấp token, không tạo phiên")
    void levelThreeLock_blocksEvenCorrectPassword() throws Exception {
        driveToLevelThreeLock();

        MvcResult correct = loginRaw(EMAIL, PASSWORD);

        assertThat(statusOf(correct)).isEqualTo(429);
        assertThat(body(correct)).doesNotContain("accessToken").doesNotContain("refreshToken");
        assertThat(sessionKeys(EMAIL)).isEmpty();
    }

    @Value("${app.login.expiration-minutes-3}")
    private int lockLv3Minutes;

    @Value("${app.login.attempt.duration-minutes}")
    private int counterMinutes;

    @Test
    @DisplayName("Cấu hình thật: thời gian khoá mức 3 phải >= thời hạn bộ đếm")
    void levelThreeLock_isNotShorterThanFailureCounter() {
        assertThat(lockLv3Minutes)
                .as("app.login.expiration-minutes-3 (" + lockLv3Minutes
                        + ") phải >= app.login.attempt.duration-minutes (" + counterMinutes
                        + "), nếu không khi khoá hết bộ đếm còn sống và người dùng bị khoá lại ngay")
                .isGreaterThanOrEqualTo(counterMinutes);
    }

    @Test
    @DisplayName("[Đối chứng] Khoá mức 3 hết hạn thật sự (khoá và bộ đếm cùng hết) thì đăng nhập đúng được và chu kỳ bắt đầu lại")
    void correctLoginAfterLevelThreeFullyExpires_succeedsAndResetsTheCounter() throws Exception {
        driveToLevelThreeLock();
        redis.delete(LOCK_KEY);
        redis.delete(FAIL_PREFIX + EMAIL);

        assertThat(statusOf(loginRaw(EMAIL, PASSWORD))).as("đăng nhập đúng sau khi hết khoá").isEqualTo(200);

        for (int i = 0; i < LOCK_ATTEMPTS_1; i++) {
            assertThat(wrongAttempt()).as("lần sai thứ " + (i + 1) + " sau khi làm mới").isEqualTo(401);
        }
        assertThat(wrongAttempt()).as("bộ đếm bắt đầu lại từ đầu nên khoá mức 1 xuất hiện lại").isEqualTo(429);
    }

    @Test
    @DisplayName("Lần sai kích hoạt khoá mức 3 trả thông báo yêu cầu đặt lại mật khẩu")
    void levelThreeTriggeringAttempt_asksForPasswordReset() throws Exception {
        driveToJustBeforeLevelThree();

        MvcResult result = loginRaw(EMAIL, WRONG_PASSWORD);

        assertThat(statusOf(result)).isEqualTo(429);
        assertThat(jsonString(result, "$.message")).contains("đặt lại mật khẩu");
    }

    @Test
    @DisplayName("[GIẢ THUYẾT] Khi đang bị khoá mức 3, các lần thử tiếp theo cũng phải báo đặt lại mật khẩu (không phải đếm ngược hàng triệu giây)")
    void levelThreeLock_laterAttempts_alsoAskForPasswordReset() throws Exception {
        driveToLevelThreeLock();

        MvcResult later = loginRaw(EMAIL, PASSWORD);

        assertThat(statusOf(later)).isEqualTo(429);
        assertThat(jsonString(later, "$.message"))
                .as("thông báo khi thử lại lúc đang khoá mức 3")
                .contains("đặt lại mật khẩu");
    }

    @Test
    @DisplayName("Chỉ gửi email cảnh báo đúng 1 lần, khi khoá mức 3 được đặt (không gửi ở mức 1, 2 và không gửi lại khi thử tiếp lúc đang khoá)")
    void levelThreeLock_sendsWarningEmailExactlyOnce() throws Exception {
        driveToJustBeforeLevelThree();
        verify(emailService, never()).sendTryToLoginWarning(anyString());

        assertThat(wrongAttempt()).isEqualTo(429);
        verify(emailService, timeout(3000)).sendTryToLoginWarning(EMAIL);

        wrongAttempt();
        loginRaw(EMAIL, PASSWORD);
        verify(emailService, after(500).times(1)).sendTryToLoginWarning(anyString());
    }
}