package com.backend.backend.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

public class OtpServiceTest {

    private static final String SECRET = "unit-test-otp-secret-0123456789-abcdef";
    private static final String OTHER_SECRET = "another-secret-another-secret-9999999";

    private final OtpService otpService = new OtpService(SECRET);

    private static String hmacSha256Hex(String key, String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] out = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        return java.util.HexFormat.of().formatHex(out);
    }

    @Test
    @DisplayName("generate: luôn trả chuỗi đúng 8 chữ số")
    void generate_returnsEightDigits() {
        for (int i = 0; i < 1000; i++) {
            assertThat(otpService.generate()).matches("^\\d{8}$");
        }
    }

    @Test
    @DisplayName("generate: số nhỏ được đệm 0 ở đầu (xác suất 5000 mẫu không có OTP nào bắt đầu bằng '0' là gần 0)")
    void generate_padsWithLeadingZeros() {
        boolean sawLeadingZero = false;
        for (int i = 0; i < 5000; i++) {
            String otp = otpService.generate();
            assertThat(otp).hasSize(8);
            sawLeadingZero |= otp.startsWith("0");
        }
        assertThat(sawLeadingZero).isTrue();
    }

    @Test
    @DisplayName("generate: không trả giá trị cố định (1000 mã phải có ít nhất 990 mã khác nhau)")
    void generate_isNotConstant() {
        Set<String> distinct = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            distinct.add(otpService.generate());
        }
        assertThat(distinct.size()).isGreaterThanOrEqualTo(990);
    }

    @Test
    @DisplayName("hash: đúng là HMAC-SHA256 theo secret, xuất hex chữ thường 64 ký tự")
    void hash_isHmacSha256LowercaseHex() throws Exception {
        String hash = otpService.hash("01234567");

        assertThat(hash).matches("^[0-9a-f]{64}$");
        assertThat(hash).isEqualTo(hmacSha256Hex(SECRET, "01234567"));
    }

    @Test
    @DisplayName("hash: ổn định với cùng input, khác nhau với OTP khác, khác nhau với secret khác")
    void hash_deterministic_andInputAndSecretBound() {
        OtpService other = new OtpService(OTHER_SECRET);

        assertThat(otpService.hash("01234567")).isEqualTo(otpService.hash("01234567"));
        assertThat(otpService.hash("01234567")).isNotEqualTo(otpService.hash("01234568"));
        assertThat(otpService.hash("01234567")).isNotEqualTo(other.hash("01234567"));
    }

    @Test
    @DisplayName("matches: khớp đúng OTP, sai OTP thì không, khác secret thì không")
    void matches_correctWrongAndOtherSecret() {
        String hash = otpService.hash("01234567");
        OtpService other = new OtpService(OTHER_SECRET);

        assertThat(otpService.matches("01234567", hash)).isTrue();
        assertThat(otpService.matches("76543210", hash)).isFalse();
        assertThat(other.matches("01234567", hash)).isFalse();
    }

    @ParameterizedTest(name = "otp nhập = [{0}]")
    @ValueSource(strings = { "", " 01234567", "01234567 ", "0123456", "012345678", "1234567", "01234567\n" })
    @DisplayName("matches: OTP gần đúng (thiếu, thừa, khoảng trắng, xuống dòng, mất số 0 đầu) đều bị từ chối")
    void matches_nearMissOtp_isRejected(String typed) {
        String hash = otpService.hash("01234567");

        assertThat(otpService.matches(typed, hash)).isFalse();
    }

    @Test
    @DisplayName("matches: hash lưu sai dạng (viết hoa, thừa khoảng trắng, rút ngắn, rỗng) => false")
    void matches_malformedStoredHash_isRejected() {
        String hash = otpService.hash("01234567");

        assertThat(otpService.matches("01234567", hash.toUpperCase())).isFalse();
        assertThat(otpService.matches("01234567", hash + " ")).isFalse();
        assertThat(otpService.matches("01234567", hash.substring(0, 63))).isFalse();
        assertThat(otpService.matches("01234567", "")).isFalse();
    }

    @Test
    @DisplayName("matches: truyền null cho rawOtp hoặc storedHash => trả false, không ném NullPointerException")
    void matches_withNullInputs_returnsFalse() {
        String hash = otpService.hash("12345678");

        assertThat(otpService.matches(null, hash)).isFalse();
        assertThat(otpService.matches("12345678", null)).isFalse();
        assertThat(otpService.matches(null, null)).isFalse();
    }

    @ParameterizedTest(name = "secret = [{0}]")
    @NullAndEmptySource
    @ValueSource(strings = { "   ", "\t", "\n" })
    @DisplayName("Constructor: secret null, rỗng hoặc toàn khoảng trắng => IllegalStateException ngay khi khởi tạo")
    void constructor_blankSecret_throwsIllegalState(String secret) {
        assertThatThrownBy(() -> new OtpService(secret))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.otp.secret");
    }

    @ParameterizedTest(name = "độ dài secret = {0}")
    @ValueSource(ints = { 1, 8, 16, 31 })
    @DisplayName("[GIẢ THUYẾT] Constructor: secret ngắn hơn 32 byte phải bị từ chối (fail-fast giống JwtService)")
    void constructor_weakSecret_shouldFailFast(int length) {
        assertThatThrownBy(() -> new OtpService("k".repeat(length)))
                .as("OtpService vẫn khởi tạo được với secret chỉ " + length + " byte")
                .isInstanceOfAny(IllegalStateException.class, IllegalArgumentException.class);
    }

    @Test
    @DisplayName("[Đối chứng] Constructor: secret đúng 32 byte vẫn dùng bình thường")
    void constructor_secretOf32Bytes_works() {
        OtpService service = new OtpService("k".repeat(32));

        assertThat(service.matches("01234567", service.hash("01234567"))).isTrue();
    }

    @Test
    @DisplayName("Đồng thời: 8 luồng cùng generate và hash => mã luôn đúng định dạng, hash luôn cùng một giá trị (không dùng chung Mac)")
    void generateAndHash_areThreadSafe() throws Exception {
        String expectedHash = otpService.hash("01234567");
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<Boolean>> futures = new ArrayList<>();
            for (int t = 0; t < 8; t++) {
                futures.add(pool.submit(() -> {
                    for (int i = 0; i < 500; i++) {
                        if (!otpService.generate().matches("^\\d{8}$")) {
                            return false;
                        }
                        if (!expectedHash.equals(otpService.hash("01234567"))) {
                            return false;
                        }
                    }
                    return true;
                }));
            }
            for (Future<Boolean> f : futures) {
                assertThat(f.get(30, TimeUnit.SECONDS)).isTrue();
            }
        } finally {
            pool.shutdownNow();
        }
    }
}