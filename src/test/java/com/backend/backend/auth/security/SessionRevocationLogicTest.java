package com.backend.backend.auth.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MvcResult;

import com.backend.backend.auth.model.Role;
import com.backend.backend.auth.service.RefreshTokenService;

public class SessionRevocationLogicTest extends SecurityIntegrationTestBase {

    private static final String EMAIL = "an@gmail.com";

    @MockitoSpyBean
    private RefreshTokenService refreshTokenService;

    @BeforeEach
    void seedUser() {
        createUser(EMAIL, PASSWORD, Role.USER, 1, 1);
    }

    @Test
    @DisplayName("[GIẢ THUYẾT] Refresh chen vào giữa lúc logout: logout không được báo 200 khi phiên mới vẫn còn sống")
    void logout_whenRefreshInterleaves_mustNotReportSuccessWithLiveSession() throws Exception {
        Tokens tokens = loginOk(EMAIL, PASSWORD);
        AtomicBoolean interleaved = new AtomicBoolean(false);
        AtomicReference<Tokens> rotated = new AtomicReference<>();

        doAnswer(invocation -> {
            if (interleaved.compareAndSet(false, true)) {
                rotated.set(refreshOk(tokens.refresh()));
            }
            return invocation.callRealMethod();
        }).when(refreshTokenService).validateAndRevoke(anyString(), anyString(), anyString());

        MvcResult logout = logoutRaw(tokens.refresh());

        assertThat(interleaved.get()).as("tiền điều kiện: refresh đã chen vào giữa").isTrue();
        assertThat(rotated.get()).isNotNull();
        if (statusOf(logout) == 200) {
            assertThat(sessionKeys(EMAIL))
                    .as("logout báo thành công nhưng phiên mới do refresh tạo ra vẫn còn trong Redis").isEmpty();
            assertThat(statusOf(getWithToken(USER_URL, rotated.get().access())))
                    .as("access token mới vẫn dùng được sau khi logout báo thành công").isEqualTo(401);
        }
    }
}
