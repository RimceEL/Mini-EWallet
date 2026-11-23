package com.backend.backend.global;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

public class ValidationUtilsTest {

    private static int utf8Bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }

    @Test
    @DisplayName("isValidPassword: đúng 72 byte ASCII và đủ hoa + ký tự đặc biệt => hợp lệ")
    void password_exactly72BytesAscii_isValid() {
        String pw = "A!" + "a".repeat(70);
        assertThat(utf8Bytes(pw)).isEqualTo(72);
        assertThat(ValidationUtils.isValidPassword(pw)).isTrue();
    }

    @Test
    @DisplayName("isValidPassword: đúng 72 byte nhưng có ký tự tiếng Việt 3 byte => hợp lệ (đếm byte, không đếm ký tự)")
    void password_exactly72BytesMultibyte_isValid() {
        String pw = "A!" + "ệ".repeat(23) + "a";
        assertThat(utf8Bytes(pw)).isEqualTo(72);
        assertThat(ValidationUtils.isValidPassword(pw)).isTrue();
    }

    @Test
    @DisplayName("isValidPassword: 73 byte ASCII bị từ chối")
    void password_73BytesAscii_isRejected() {
        String pw = "A!" + "a".repeat(71);
        assertThat(utf8Bytes(pw)).isEqualTo(73);
        assertThat(ValidationUtils.isValidPassword(pw)).isFalse();
    }

    @Test
    @DisplayName("isValidPassword: 74 byte do ký tự tiếng Việt bị từ chối dù chỉ có 26 ký tự")
    void password_over72BytesVietnamese_isRejected() {
        String pw = "A!" + "ệ".repeat(24);
        assertThat(pw.length()).isLessThan(72);
        assertThat(ValidationUtils.isValidPassword(pw)).isFalse();
    }

    @Test
    @DisplayName("isValidPassword: emoji 4 byte, 70 byte hợp lệ, 74 byte bị từ chối")
    void password_emoji_countedInBytes() {
        assertThat(ValidationUtils.isValidPassword("A!" + "😀".repeat(17))).isTrue();
        assertThat(ValidationUtils.isValidPassword("A!" + "😀".repeat(18))).isFalse();
    }

    @Test
    @DisplayName("isValidPassword: 100.000 ký tự bị từ chối")
    void password_hugeInput_isRejected() {
        assertThat(ValidationUtils.isValidPassword("A!" + "a".repeat(100_000))).isFalse();
    }

    @Test
    @DisplayName("isValidPassword: đúng 12 ký tự hợp lệ, 11 ký tự bị từ chối")
    void password_lengthBoundary_is12Chars() {
        assertThat(ValidationUtils.isValidPassword("A!" + "a".repeat(10))).isTrue();
        assertThat(ValidationUtils.isValidPassword("A!" + "a".repeat(9))).isFalse();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = { "   ", "Short1!A" })
    @DisplayName("isValidPassword: null, rỗng, toàn khoảng trắng, quá ngắn => false")
    void password_nullEmptyOrTooShort_isRejected(String invalid) {
        assertThat(ValidationUtils.isValidPassword(invalid)).isFalse();
    }

    @Test
    @DisplayName("isValidPassword: thiếu chữ hoa => false (kể cả chữ thường có dấu như 'ệ')")
    void password_withoutUppercase_isRejected() {
        assertThat(ValidationUtils.isValidPassword("khongcochuhoa123!")).isFalse();
        assertThat(ValidationUtils.isValidPassword("ệ!" + "a".repeat(10))).isFalse();
    }

    @Test
    @DisplayName("isValidPassword: chữ hoa có dấu tiếng Việt được tính là chữ hoa")
    void password_vietnameseUppercase_counts() {
        assertThat(ValidationUtils.isValidPassword("Ệ!" + "a".repeat(10))).isTrue();
    }

    @Test
    @DisplayName("isValidPassword: thiếu ký tự đặc biệt => false (chữ số và khoảng trắng KHÔNG phải ký tự đặc biệt)")
    void password_withoutSpecialChar_isRejected() {
        assertThat(ValidationUtils.isValidPassword("KHONGCODACBIET123")).isFalse();
        assertThat(ValidationUtils.isValidPassword("Aa1" + "a".repeat(9))).isFalse();
        assertThat(ValidationUtils.isValidPassword("Aa " + "a".repeat(9))).isFalse();
    }

    @ParameterizedTest(name = "ký tự đặc biệt = [{0}]")
    @ValueSource(strings = { "!", "@", "#", "$", "%", "^", "_", "-", "+", "=", "~", "|", "<", "€" })
    @DisplayName("isValidPassword: các ký tự đặc biệt phổ biến đều được chấp nhận")
    void password_commonSpecialChars_areAccepted(String special) {
        assertThat(ValidationUtils.isValidPassword("A" + "a".repeat(10) + special)).isTrue();
    }

    @Test
    @DisplayName("[Thiết kế] isValidPassword: mật khẩu chứa xuống dòng bị từ chối (regex '.' không khớp ký tự xuống dòng)")
    void password_withLineBreak_isRejected() {
        assertThat(ValidationUtils.isValidPassword("A!" + "a".repeat(10) + "\n")).isFalse();
        assertThat(ValidationUtils.isValidPassword("A!" + "a".repeat(5) + "\n" + "a".repeat(5))).isFalse();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = { "   ", "\t" })
    @DisplayName("isValidEmail: null, rỗng, toàn khoảng trắng => false")
    void email_nullEmptyOrBlank_isRejected(String invalid) {
        assertThat(ValidationUtils.isValidEmail(invalid)).isFalse();
    }

    @ParameterizedTest(name = "email = [{0}]")
    @ValueSource(strings = { "an@gmail.com", "AN@GMAIL.COM", "a.b+tag@sub.gmail.co.uk", "o'brien@gmail.com",
            "user_name-1@my-domain.vn", "a@b.c" })
    @DisplayName("isValidEmail: các định dạng email hợp lệ phổ biến")
    void email_validFormats_areAccepted(String email) {
        assertThat(ValidationUtils.isValidEmail(email)).isTrue();
    }

    @ParameterizedTest(name = "email = [{0}]")
    @ValueSource(strings = {
            "plainaddress",
            "@gmail.com",
            "an@",
            "an@@gmail.com",
            "an@gmail..com",
            "an@-gmail.com",
            "an@gmail-.com",
            "an@gmail.com.",
            "an gmail@x.com",
            " an@gmail.com",
            "an@gmail.com ",
            "an@gmail.com\n",
            "an@gmail.com\r\nBcc: evil@x.com",
            "ân@gmail.com",
            "an@gmàil.com",
            "an@gmail,com",
            "an@gmail.com,evil@x.com",
            "an@gmail.com;evil@x.com",
            "\"an\"@gmail.com",
            "an(comment)@gmail.com" })
    @DisplayName("isValidEmail: định dạng sai, ký tự lạ, khoảng trắng, xuống dòng (chèn header mail) => false")
    void email_invalidFormats_areRejected(String email) {
        assertThat(ValidationUtils.isValidEmail(email)).isFalse();
    }

    @Test
    @DisplayName("isValidEmail: biên 72 byte, đúng 72 hợp lệ, 73 bị từ chối")
    void email_72ByteBoundary() {
        String exactly72 = "a".repeat(62) + "@gmail.com";
        String exactly73 = "a".repeat(63) + "@gmail.com";
        assertThat(utf8Bytes(exactly72)).isEqualTo(72);
        assertThat(utf8Bytes(exactly73)).isEqualTo(73);

        assertThat(ValidationUtils.isValidEmail(exactly72)).isTrue();
        assertThat(ValidationUtils.isValidEmail(exactly73)).isFalse();
    }

    @Test
    @DisplayName("isValidEmail: nhãn domain tối đa 63 ký tự, 63 hợp lệ, 64 bị từ chối (cả hai đều dưới 72 byte)")
    void email_domainLabelLimit() {
        String label63 = "a@" + "b".repeat(63) + ".com";
        String label64 = "a@" + "b".repeat(64) + ".com";
        assertThat(utf8Bytes(label64)).isLessThan(72);

        assertThat(ValidationUtils.isValidEmail(label63)).isTrue();
        assertThat(ValidationUtils.isValidEmail(label64)).isFalse();
    }

    @ParameterizedTest(name = "email = [{0}]")
    @ValueSource(strings = { ".an@gmail.com", "an.@gmail.com", "an..b@gmail.com" })
    @DisplayName("[GIẢ THUYẾT] isValidEmail: phần local bắt đầu/kết thúc bằng dấu chấm hoặc có 2 dấu chấm liền nhau (sai RFC 5322) phải bị từ chối")
    void email_localPartWithBadDots_shouldBeRejected(String email) {
        assertThat(ValidationUtils.isValidEmail(email)).as("email '" + email + "' được chấp nhận").isFalse();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = { "      ", "   abc   ", "abcde" })
    @DisplayName("isValidUsername: null, rỗng, toàn khoảng trắng, dưới 6 ký tự sau trim => false")
    void username_nullBlankOrTooShort_isRejected(String invalid) {
        assertThat(ValidationUtils.isValidUsername(invalid)).isFalse();
    }

    @ParameterizedTest(name = "username = [{0}]")
    @ValueSource(strings = { "abcdef", "nguoidung01", "ệệệệệệ" })
    @DisplayName("isValidUsername: đúng 6 ký tự (kể cả tiếng Việt) hoặc dài hơn => hợp lệ")
    void username_atLeast6Chars_isValid(String username) {
        assertThat(ValidationUtils.isValidUsername(username)).isTrue();
    }

    @Test
    @DisplayName("isValidUsername: biên 72 byte, ASCII và tiếng Việt")
    void username_72ByteBoundary() {
        assertThat(ValidationUtils.isValidUsername("a".repeat(72))).isTrue();
        assertThat(ValidationUtils.isValidUsername("a".repeat(73))).isFalse();
        assertThat(ValidationUtils.isValidUsername("ệ".repeat(24))).isTrue();
        assertThat(ValidationUtils.isValidUsername("ệ".repeat(25))).isFalse();
    }

    @Test
    @DisplayName("[GIẢ THUYẾT] isValidUsername: 6 khoảng trắng Unicode (U+3000) không phải username hợp lệ")
    void username_unicodeWhitespaceOnly_shouldBeRejected() {
        String blank = "\u3000".repeat(6);
        assertThat(ValidationUtils.isValidUsername(blank)).as("username toàn khoảng trắng Unicode được chấp nhận")
                .isFalse();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = { "      ", "  An  ", "Nguye" })
    @DisplayName("isValidFullName: null, rỗng, toàn khoảng trắng, dưới 6 ký tự sau trim => false")
    void fullName_nullBlankOrTooShort_isRejected(String invalid) {
        assertThat(ValidationUtils.isValidFullName(invalid)).isFalse();
    }

    @ParameterizedTest(name = "fullName = [{0}]")
    @ValueSource(strings = { "Nguyễn An", "Nguyen Van An", "Trần Thị Bích Ngọc" })
    @DisplayName("isValidFullName: tên thật tiếng Việt có dấu => hợp lệ")
    void fullName_realVietnameseNames_areValid(String name) {
        assertThat(ValidationUtils.isValidFullName(name)).isTrue();
    }

    @Test
    @DisplayName("isValidFullName: biên 72 byte, ASCII và tiếng Việt")
    void fullName_72ByteBoundary() {
        assertThat(ValidationUtils.isValidFullName("N".repeat(72))).isTrue();
        assertThat(ValidationUtils.isValidFullName("N".repeat(73))).isFalse();
        assertThat(ValidationUtils.isValidFullName("ệ".repeat(24))).isTrue();
        assertThat(ValidationUtils.isValidFullName("ệ".repeat(25))).isFalse();
    }

    @Test
    @DisplayName("[GIẢ THUYẾT] isValidFullName: 6 khoảng trắng Unicode (U+3000) không phải họ tên hợp lệ")
    void fullName_unicodeWhitespaceOnly_shouldBeRejected() {
        String blank = "\u3000".repeat(6);
        assertThat(ValidationUtils.isValidFullName(blank)).as("họ tên toàn khoảng trắng Unicode được chấp nhận")
                .isFalse();
    }
}