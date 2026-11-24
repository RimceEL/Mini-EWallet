package com.backend.backend.auth.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import com.backend.backend.auth.dto.RegisterRequest;
import com.backend.backend.auth.model.Role;
import com.backend.backend.auth.model.User;
import com.backend.backend.transaction.MySqlIntegrationTestBase;
import com.zaxxer.hikari.HikariDataSource;

@SpringJUnitConfig(AuthRepositoryTest.Config.class)
@Timeout(value = 120, unit = TimeUnit.SECONDS)
public class AuthRepositoryTest extends MySqlIntegrationTestBase {

    @TestConfiguration(proxyBeanMethods = false)
    @Import(AuthRepository.class)
    static class Config {

        @Bean(destroyMethod = "close")
        HikariDataSource dataSource() {
            return newDataSource(10, 2, 10000);
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }
    }

    @Autowired
    private AuthRepository authRepository;

    private static final String EMAIL = "an@gmail.com";
    private static final String HASH = "$2a$04$abcdefghijklmnopqrstuuABCDEFGHIJKLMNOPQRSTUVWXYZ01234";

    private static RegisterRequest request(String email) {
        return RegisterRequest.builder()
                .email(email)
                .username("nguoidung01")
                .password(HASH)
                .fullName("Nguyen Van An")
                .build();
    }

    private int countUsers() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM users", Integer.class);
    }

    private int isVerifiedOf(String email) {
        return jdbc.queryForObject("SELECT is_verified FROM users WHERE email = ?", Integer.class, email);
    }

    private String passwordOf(String email) {
        return jdbc.queryForObject("SELECT password FROM users WHERE email = ?", String.class, email);
    }

    private static User userWithNewPassword(String email, String newHash) {
        return User.builder().email(email).password(newHash).build();
    }

    @Test
    @DisplayName("findUserByEmail: tìm thấy => map đủ các cột (id UUID, email, tên hiển thị, hash, role, họ tên, cờ, thời gian)")
    void findUserByEmail_existing_mapsAllColumns() {
        authRepository.register(request(EMAIL));

        Optional<User> found = authRepository.findUserByEmail(EMAIL);

        assertThat(found).isPresent();
        User user = found.get();
        assertThat(user.getId()).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
        assertThat(user.getEmail()).isEqualTo(EMAIL);
        assertThat(user.getUsername()).as("getUsername() của UserDetails trả về email").isEqualTo(EMAIL);
        assertThat(user.getName()).as("tên hiển thị lấy từ cột username").isEqualTo("nguoidung01");
        assertThat(user.getPassword()).isEqualTo(HASH);
        assertThat(user.getRole()).isEqualTo(Role.USER);
        assertThat(user.getFullName()).isEqualTo("Nguyen Van An");
        assertThat(user.getPhoneNumber()).isNull();
        assertThat(user.getAddress()).isNull();
        assertThat(user.getIsActive()).isEqualTo(1);
        assertThat(user.getIsVerified()).isZero();
        assertThat(user.getCreatedAt()).isNotNull();
        assertThat(user.getUpdatedAt()).isNotNull();
        assertThat(user.isAccountNonLocked()).isTrue();
        assertThat(user.isEnabled()).as("mới đăng ký thì chưa xác thực email").isFalse();
    }

    @Test
    @DisplayName("findUserByEmail: email không tồn tại => Optional.empty, không ném lỗi")
    void findUserByEmail_unknown_returnsEmpty() {
        authRepository.register(request(EMAIL));

        assertThat(authRepository.findUserByEmail("khong.ton.tai@gmail.com")).isEmpty();
    }

    @Test
    @DisplayName("findUserByEmail: email = null => Optional.empty (so sánh với NULL không khớp hàng nào), không ném lỗi")
    void findUserByEmail_null_returnsEmpty() {
        authRepository.register(request(EMAIL));

        assertThatCode(() -> authRepository.findUserByEmail(null)).doesNotThrowAnyException();
        assertThat(authRepository.findUserByEmail(null)).isEmpty();
    }

    @Test
    @DisplayName("findUserByEmail: cột role = ADMIN được map thành Role.ADMIN")
    void findUserByEmail_adminRole_isMappedToEnum() {
        authRepository.register(request(EMAIL));
        jdbc.update("UPDATE users SET role = 'ADMIN' WHERE email = ?", EMAIL);

        User user = authRepository.findUserByEmail(EMAIL).orElseThrow();

        assertThat(user.getRole()).isEqualTo(Role.ADMIN);
        assertThat(user.getAuthorities()).extracting(Object::toString).containsExactly("ROLE_ADMIN");
    }

    @Test
    @DisplayName("findUserByEmail: so sánh email PHÂN BIỆT HOA THƯỜNG (collation utf8mb4_bin) => 'AN@gmail.com' không tìm ra 'an@gmail.com'")
    void findUserByEmail_isCaseSensitive() {
        authRepository.register(request("Mixed.Case@Gmail.com"));

        assertThat(authRepository.findUserByEmail("Mixed.Case@Gmail.com")).isPresent();
        assertThat(authRepository.findUserByEmail("mixed.case@gmail.com")).isEmpty();
        assertThat(authRepository.findUserByEmail("MIXED.CASE@GMAIL.COM")).isEmpty();
    }

    @ParameterizedTest(name = "email = {0}")
    @ValueSource(strings = {
            "' OR '1'='1",
            "an@gmail.com' OR '1'='1",
            "an@gmail.com'; DROP TABLE users; --",
            "an@gmail.com\" OR \"1\"=\"1",
            "%",
            "an@gmail.%" })
    @DisplayName("findUserByEmail: chuỗi giống SQL injection hoặc ký tự LIKE (%) không khớp user nào và bảng users vẫn còn nguyên")
    void findUserByEmail_injectionAndWildcardPayloads_matchNothing(String payload) {
        authRepository.register(request(EMAIL));

        assertThat(authRepository.findUserByEmail(payload)).isEmpty();
        assertThat(countUsers()).isEqualTo(1);
    }

    @Test
    @DisplayName("register: ghi đúng các cột truyền vào (mật khẩu lưu nguyên giá trị nhận được) và để DB gán mặc định role = USER, is_active = 1, is_verified = 0")
    void register_insertsRowWithDefaults() {
        authRepository.register(request(EMAIL));

        assertThat(countUsers()).isEqualTo(1);
        var row = jdbc.queryForMap(
                "SELECT email, username, password, full_name, role, is_active, is_verified FROM users WHERE email = ?",
                EMAIL);
        assertThat(row.get("email")).isEqualTo(EMAIL);
        assertThat(row.get("username")).isEqualTo("nguoidung01");
        assertThat(row.get("password")).isEqualTo(HASH);
        assertThat(row.get("full_name")).isEqualTo("Nguyen Van An");
        assertThat(row.get("role")).isEqualTo("USER");
        assertThat(((Number) row.get("is_active")).intValue()).isEqualTo(1);
        assertThat(((Number) row.get("is_verified")).intValue()).isZero();
    }

    @Test
    @DisplayName("register: trùng email => ném DuplicateKeyException, không tạo thêm hàng và hàng cũ không bị sửa")
    void register_duplicateEmail_throwsDuplicateKeyException() {
        authRepository.register(request(EMAIL));
        RegisterRequest second = RegisterRequest.builder().email(EMAIL).username("nguoikhac").password("hash-khac")
                .fullName("Nguoi Khac").build();

        assertThatThrownBy(() -> authRepository.register(second)).isInstanceOf(DuplicateKeyException.class);

        assertThat(countUsers()).isEqualTo(1);
        assertThat(passwordOf(EMAIL)).isEqualTo(HASH);
        assertThat(authRepository.findUserByEmail(EMAIL).orElseThrow().getName()).isEqualTo("nguoidung01");
    }

    @Test
    @DisplayName("register: username và fullName = null được chấp nhận (cột cho phép NULL)")
    void register_nullOptionalFields_areAccepted() {
        RegisterRequest minimal = RegisterRequest.builder().email(EMAIL).password(HASH).build();

        authRepository.register(minimal);

        User user = authRepository.findUserByEmail(EMAIL).orElseThrow();
        assertThat(user.getName()).isNull();
        assertThat(user.getFullName()).isNull();
    }

    @Test
    @DisplayName("register: email = null hoặc password = null bị DB từ chối (NOT NULL) bằng DataIntegrityViolationException, không tạo hàng")
    void register_nullEmailOrPassword_isRejected() {
        RegisterRequest nullEmail = RegisterRequest.builder().email(null).username("nguoidung01").password(HASH)
                .fullName("Nguyen Van An").build();
        RegisterRequest nullPassword = RegisterRequest.builder().email(EMAIL).username("nguoidung01").password(null)
                .fullName("Nguyen Van An").build();

        assertThatThrownBy(() -> authRepository.register(nullEmail))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> authRepository.register(nullPassword))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(countUsers()).isZero();
    }

    @Test
    @DisplayName("register: email dài hơn 255 ký tự bị DB từ chối (không bị cắt âm thầm), không tạo hàng")
    void register_tooLongEmail_isRejectedNotTruncated() {
        String tooLong = "a".repeat(250) + "@x.com";

        assertThatThrownBy(() -> authRepository.register(request(tooLong)))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(countUsers()).isZero();
    }

    @Test
    @DisplayName("markVerified: đặt is_verified = 1 cho đúng email đó, user khác không bị ảnh hưởng, user đó đăng nhập được (isEnabled)")
    void markVerified_updatesOnlyThatUser() {
        authRepository.register(request(EMAIL));
        authRepository.register(request("khac@gmail.com"));

        authRepository.markVerified(EMAIL);

        assertThat(isVerifiedOf(EMAIL)).isEqualTo(1);
        assertThat(isVerifiedOf("khac@gmail.com")).isZero();
        assertThat(authRepository.findUserByEmail(EMAIL).orElseThrow().isEnabled()).isTrue();
        assertThat(authRepository.findUserByEmail("khac@gmail.com").orElseThrow().isEnabled()).isFalse();
    }

    @Test
    @DisplayName("markVerified: gọi nhiều lần vẫn 1 (idempotent) và không đổi cột khác (is_active, password, role)")
    void markVerified_isIdempotent_andKeepsOtherColumns() {
        authRepository.register(request(EMAIL));
        jdbc.update("UPDATE users SET is_active = 0 WHERE email = ?", EMAIL);

        authRepository.markVerified(EMAIL);
        authRepository.markVerified(EMAIL);

        User user = authRepository.findUserByEmail(EMAIL).orElseThrow();
        assertThat(user.getIsVerified()).isEqualTo(1);
        assertThat(user.getIsActive()).as("markVerified không được mở khoá tài khoản").isZero();
        assertThat(user.getPassword()).isEqualTo(HASH);
        assertThat(user.getRole()).isEqualTo(Role.USER);
    }

    @Test
    @DisplayName("markVerified: email không tồn tại, khác hoa/thường hoặc null => không ném lỗi và không đổi hàng nào")
    void markVerified_unknownEmail_changesNothing() {
        authRepository.register(request(EMAIL));

        assertThatCode(() -> {
            authRepository.markVerified("khong.ton.tai@gmail.com");
            authRepository.markVerified("AN@gmail.com");
            authRepository.markVerified(null);
        }).doesNotThrowAnyException();

        assertThat(isVerifiedOf(EMAIL)).isZero();
    }

    @Test
    @DisplayName("updateUserPassword: đổi password của đúng email, user khác và các cột khác không đổi")
    void updateUserPassword_changesOnlyThatUsersPassword() {
        authRepository.register(request(EMAIL));
        authRepository.register(request("khac@gmail.com"));
        authRepository.markVerified(EMAIL);
        String newHash = "$2a$04$newnewnewnewnewnewnewOLDOLDOLDOLDOLDOLDOLDOLDOLDOLDOLDO";

        authRepository.updateUserPassword(userWithNewPassword(EMAIL, newHash));

        assertThat(passwordOf(EMAIL)).isEqualTo(newHash);
        assertThat(passwordOf("khac@gmail.com")).isEqualTo(HASH);
        User user = authRepository.findUserByEmail(EMAIL).orElseThrow();
        assertThat(user.getIsVerified()).isEqualTo(1);
        assertThat(user.getIsActive()).isEqualTo(1);
        assertThat(user.getRole()).isEqualTo(Role.USER);
        assertThat(user.getName()).isEqualTo("nguoidung01");
    }

    @Test
    @DisplayName("updateUserPassword: email không tồn tại, khác hoa/thường hoặc null => không ném lỗi và không đổi password của ai")
    void updateUserPassword_unknownEmail_changesNothing() {
        authRepository.register(request(EMAIL));

        assertThatCode(() -> {
            authRepository.updateUserPassword(userWithNewPassword("khong.ton.tai@gmail.com", "hash-moi"));
            authRepository.updateUserPassword(userWithNewPassword("AN@gmail.com", "hash-moi"));
            authRepository.updateUserPassword(userWithNewPassword(null, "hash-moi"));
        }).doesNotThrowAnyException();

        assertThat(passwordOf(EMAIL)).isEqualTo(HASH);
    }

    @Test
    @DisplayName("updateUserPassword: password = null bị DB từ chối (NOT NULL), password cũ được giữ nguyên")
    void updateUserPassword_nullPassword_isRejected_andKeepsOldHash() {
        authRepository.register(request(EMAIL));

        assertThatThrownBy(() -> authRepository.updateUserPassword(userWithNewPassword(EMAIL, null)))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(passwordOf(EMAIL)).isEqualTo(HASH);
    }
}
