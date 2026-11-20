package com.backend.backend.auth.security.perimeter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import com.backend.backend.auth.model.Role;
import com.backend.backend.auth.security.SecurityIntegrationTestBase;
import com.jayway.jsonpath.JsonPath;

public class SensitiveDataExposureSecurityTest extends SecurityIntegrationTestBase {

    private static final String EMAIL = "an@gmail.com";
    private static final String WALLET_URL = "/api/v1/user/wallet-details";
    private static final String TOP_UP_URL = "/api/v1/user/transaction/create/top-up";

    private static final Set<String> USER_FIELDS = Set.of(
            "id", "email", "username", "fullName", "phoneNumber", "address", "role");
    private static final Set<String> WALLET_FIELDS = Set.of(
            "id", "balance", "currency", "status", "createdAt", "updatedAt");
    private static final Set<String> TRANSACTION_FIELDS = Set.of(
            "referenceId", "type", "status", "sourceWalletId", "destinationWalletId",
            "amount", "description", "createdAt");

    private static final List<String> INTERNAL_MARKERS = List.of(
            "password", "$2a$", "$2b$", "$2y$", "isActive", "isVerified", "is_active", "is_verified",
            "authorities", "accountNonLocked", "accountNonExpired", "credentialsNonExpired", "enabled",
            "pending", "version", "user_id", "userId");

    @BeforeEach
    void seedUser() {
        createUser(EMAIL, PASSWORD, Role.USER, 1, 1);
        jdbc.update("UPDATE users SET phone_number = ?, address = ? WHERE email = ?",
                "0900000000", "1 Duong Test", EMAIL);
    }

    private void openWalletInDb() {
        jdbc.update("INSERT INTO wallets (user_id) SELECT id FROM users WHERE email = ?", EMAIL);
    }

    private static void assertOnlyAllowedKeys(MvcResult result, String jsonPath, Set<String> allowed) throws Exception {
        Map<String, Object> data = JsonPath.read(body(result), jsonPath);
        assertThat(data.keySet()).as("các trường trong " + jsonPath).isSubsetOf(allowed);
    }

    @Test
    @DisplayName("user-details chỉ trả các trường cho phép, không rò hash mật khẩu hay cờ nội bộ")
    void userDetails_exposesOnlyWhitelistedFields() throws Exception {
        Tokens tokens = loginOk(EMAIL, PASSWORD);

        MvcResult result = getWithToken(USER_URL, tokens.access());

        assertThat(statusOf(result)).isEqualTo(200);
        assertOnlyAllowedKeys(result, "$.data", USER_FIELDS);
        assertThat(body(result)).doesNotContain(INTERNAL_MARKERS.toArray(new String[0]));
        assertThat(jsonString(result, "$.data.email")).isEqualTo(EMAIL);
    }

    @Test
    @DisplayName("wallet-details chỉ trả các trường cho phép, không rò user_id, pending, version")
    void walletDetails_exposesOnlyWhitelistedFields() throws Exception {
        openWalletInDb();
        Tokens tokens = loginOk(EMAIL, PASSWORD);

        MvcResult result = getWithToken(WALLET_URL, tokens.access());

        assertThat(statusOf(result)).as(body(result)).isEqualTo(200);
        assertOnlyAllowedKeys(result, "$.data", WALLET_FIELDS);
        assertThat(body(result)).doesNotContain(INTERNAL_MARKERS.toArray(new String[0]));
    }

    @Test
    @DisplayName("Tạo giao dịch nạp tiền chỉ trả các trường cho phép của giao dịch")
    void createdTransaction_exposesOnlyWhitelistedFields() throws Exception {
        openWalletInDb();
        Tokens tokens = loginOk(EMAIL, PASSWORD);

        MvcResult result = mockMvc.perform(post(TOP_UP_URL)
                .header("Authorization", "Bearer " + tokens.access())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":1000,\"description\":\"nap thu\"}"))
                .andReturn();

        assertThat(statusOf(result)).as(body(result)).isEqualTo(200);
        assertOnlyAllowedKeys(result, "$.data", TRANSACTION_FIELDS);
        assertThat(body(result)).doesNotContain("otp", "token", "password");
    }

    @Test
    @DisplayName("Đăng nhập, refresh, đăng xuất không đặt cookie nào")
    void authEndpoints_setNoCookies() throws Exception {
        MvcResult login = loginRaw(EMAIL, PASSWORD);
        Tokens tokens = parseTokens(login);
        MvcResult refresh = refreshRaw(tokens.refresh());
        Tokens rotated = parseTokens(refresh);
        MvcResult logout = logoutRaw(rotated.refresh());

        for (MvcResult result : List.of(login, refresh, logout)) {
            assertThat(statusOf(result)).isEqualTo(200);
            assertThat(result.getResponse().getHeaders("Set-Cookie")).isEmpty();
            assertThat(result.getResponse().getCookies()).isEmpty();
        }
    }

    @Test
    @DisplayName("Đăng nhập sai không bị rò rỉ email hay mật khẩu đã gửi")
    void failedLogin_doesNotEchoCredentials() throws Exception {
        String wrongPassword = "MarkerMatKhauSai!987654";

        MvcResult result = loginRaw(EMAIL, wrongPassword);

        assertThat(statusOf(result)).isEqualTo(401);
        assertThat(body(result)).doesNotContain(wrongPassword).doesNotContain(EMAIL);
    }

    @Test
    @DisplayName("Token rác không bị rò rỉ trong thông báo lỗi 401")
    void rejectedToken_isNotEchoedBack() throws Exception {
        String marker = "MARKER_TOKEN_KHONG_DUOC_PHAN_CHIEU";

        MvcResult result = mockMvc.perform(get(USER_URL).header("Authorization", "Bearer " + marker)).andReturn();

        assertThat(statusOf(result)).isEqualTo(401);
        assertThat(body(result)).doesNotContain(marker);
    }

    @Test
    @DisplayName("Refresh token rác không bị rò rỉ trong thông báo lỗi")
    void rejectedRefreshToken_isNotEchoedBack() throws Exception {
        String marker = "MARKER_REFRESH_KHONG_DUOC_PHAN_CHIEU";

        MvcResult result = refreshRaw(marker);

        assertThat(statusOf(result)).isEqualTo(401);
        assertThat(body(result)).doesNotContain(marker);
    }
}
