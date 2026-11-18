package com.backend.backend.auth.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.backend.backend.auth.model.Role;
import com.backend.backend.auth.model.User;
import com.backend.backend.auth.service.JwtService;

public class JwtSecretConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(JwtService.class)
            .withPropertyValues(
                    "jwt.access.expiration=60000",
                    "jwt.refresh.expiration=600000",
                    "jwt.min_secret_bytes=32");

    private static User user() {
        return User.builder()
                .id("user-1")
                .email("an@gmail.com")
                .username("tester01")
                .password("khong-dung-toi")
                .role(Role.USER)
                .isActive(1)
                .isVerified(1)
                .build();
    }

    @Test
    @DisplayName("[Đối chứng] Khoá đúng 32 byte: ứng dụng khởi động và ký/đọc được token")
    void secretOfExactly32Bytes_worksEndToEnd() {
        runner.withPropertyValues("jwt.secret=" + "k".repeat(32)).run(context -> {
            assertThat(context).hasNotFailed();
            JwtService jwtService = context.getBean(JwtService.class);

            String token = jwtService.accessTokenGenerate(user(), "jti-1");

            assertThat(jwtService.extractUsername(token)).isEqualTo("an@gmail.com");
            assertThat(jwtService.extractJti(token)).isEqualTo("jti-1");
        });
    }

    @ParameterizedTest(name = "độ dài khoá = {0} ký tự")
    @ValueSource(ints = { 0, 1, 8, 16, 31 })
    @DisplayName("[GIẢ THUYẾT] Khoá ngắn hơn 32 byte phải làm ứng dụng KHÔNG khởi động được (fail-fast)")
    void weakSecret_mustFailAtStartup(int length) {
        runner.withPropertyValues("jwt.secret=" + "k".repeat(length)).run(context -> assertThat(context)
                .as("ứng dụng vẫn khởi động với khoá ký chỉ " + length + " byte")
                .hasFailed());
    }
}
