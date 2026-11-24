package com.backend.backend.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import com.backend.backend.auth.model.Role;
import com.backend.backend.auth.security.SecurityIntegrationTestBase;
import com.jayway.jsonpath.JsonPath;

public class WalletControllerTest extends SecurityIntegrationTestBase {

    private static final String OPEN_WALLET_URL = "/api/v1/user/open-wallet";
    private static final String WALLET_URL = "/api/v1/user/wallet-details";

    private static final String USER_EMAIL = "an@gmail.com";
    private static final String OTHER_EMAIL = "binh@gmail.com";
    private static final String ADMIN_EMAIL = "admin@gmail.com";

    private static final DateTimeFormatter WALLET_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private String userToken;

    @BeforeEach
    void seedUser() throws Exception {
        createUser(USER_EMAIL, PASSWORD, Role.USER, 1, 1);
        userToken = loginOk(USER_EMAIL, PASSWORD).access();
    }

    private MvcResult openWallet(String accessToken) throws Exception {
        return mockMvc.perform(post(OPEN_WALLET_URL).header("Authorization", "Bearer " + accessToken)).andReturn();
    }

    private MvcResult walletDetails(String accessToken) throws Exception {
        return getWithToken(WALLET_URL, accessToken);
    }

    private int walletCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM wallets", Integer.class);
    }

    private int walletCountOf(String email) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM wallets w JOIN users u ON w.user_id = u.id WHERE u.email = ?",
                Integer.class, email);
    }

    private String walletIdOf(String email) {
        return jdbc.queryForObject(
                "SELECT w.id FROM wallets w JOIN users u ON w.user_id = u.id WHERE u.email = ?",
                String.class, email);
    }

    private void setBalance(String email, String balance) {
        jdbc.update("UPDATE wallets SET balance = ? WHERE user_id = (SELECT id FROM users WHERE email = ?)",
                new BigDecimal(balance), email);
    }

    @Test
    @DisplayName("open-wallet lần đầu => 200, code 0, không có data, DB có đúng 1 ví mặc định (0 VND, ACTIVE, pending 0) của người gọi")
    void openWallet_firstTime_createsDefaultWallet() throws Exception {
        MvcResult result = openWallet(userToken);

        assertThat(statusOf(result)).as(body(result)).isEqualTo(200);
        assertThat((Integer) JsonPath.read(body(result), "$.code")).isZero();
        assertThat(jsonString(result, "$.message")).isEqualTo("Success");
        assertThat(body(result)).as("data null phải bị bỏ khỏi JSON").doesNotContain("\"data\"");

        assertThat(walletCount()).isEqualTo(1);
        assertThat(walletCountOf(USER_EMAIL)).isEqualTo(1);
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT balance, currency, status, pending FROM wallets WHERE id = ?", walletIdOf(USER_EMAIL));
        assertThat((BigDecimal) row.get("balance")).isEqualByComparingTo("0");
        assertThat(row.get("currency")).isEqualTo("VND");
        assertThat(row.get("status")).isEqualTo("ACTIVE");
        assertThat(((Number) row.get("pending")).intValue()).isZero();
    }

    @Test
    @DisplayName("open-wallet lần 2 => 409 đúng định dạng lỗi, vẫn chỉ 1 ví và số dư không bị reset")
    void openWallet_secondTime_returns409_andKeepsExistingWallet() throws Exception {
        assertThat(statusOf(openWallet(userToken))).isEqualTo(200);
        String walletId = walletIdOf(USER_EMAIL);
        setBalance(USER_EMAIL, "500000");

        MvcResult second = openWallet(userToken);

        assertThat(statusOf(second)).as(body(second)).isEqualTo(409);
        assertThat((Integer) JsonPath.read(body(second), "$.status")).isEqualTo(409);
        assertThat(jsonString(second, "$.error")).isEqualTo("Conflict");
        assertThat(jsonString(second, "$.message")).isEqualTo("This user has already open wallet");
        assertThat(jsonString(second, "$.path")).isEqualTo(OPEN_WALLET_URL);
        assertThat(walletCount()).isEqualTo(1);
        assertThat(walletIdOf(USER_EMAIL)).as("ví cũ không bị thay").isEqualTo(walletId);
        assertThat(jdbc.queryForObject("SELECT balance FROM wallets WHERE id = ?", BigDecimal.class, walletId))
                .isEqualByComparingTo("500000");
    }

    @Test
    @DisplayName("open-wallet bỏ qua body và query param: không thể mở ví hộ người khác")
    void openWallet_ignoresBodyAndParams_onlyOpensForCaller() throws Exception {
        createUser(OTHER_EMAIL, PASSWORD, Role.USER, 1, 1);

        MvcResult result = mockMvc.perform(post(OPEN_WALLET_URL + "?email=" + OTHER_EMAIL + "&userEmail=" + OTHER_EMAIL)
                .header("Authorization", "Bearer " + userToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + OTHER_EMAIL + "\",\"userId\":\"x\"}")).andReturn();

        assertThat(statusOf(result)).as(body(result)).isEqualTo(200);
        assertThat(walletCountOf(USER_EMAIL)).isEqualTo(1);
        assertThat(walletCountOf(OTHER_EMAIL)).isZero();
    }

    @Test
    @DisplayName("Race: 8 request open-wallet cùng lúc => đúng 1 request 200, còn lại 409, không 5xx, DB đúng 1 ví")
    void openWallet_concurrent_createsExactlyOneWallet() throws Exception {
        List<Integer> statuses = runConcurrently(8, () -> statusOf(openWallet(userToken)));

        assertThat(statuses).as("các status: " + statuses).noneMatch(status -> status >= 500);
        assertThat(statuses.stream().filter(status -> status == 200).count()).isEqualTo(1);
        assertThat(statuses.stream().filter(status -> status == 409).count()).isEqualTo(7);
        assertThat(walletCountOf(USER_EMAIL)).isEqualTo(1);
    }

    @Test
    @DisplayName("wallet-details khi chưa mở ví => 404 đúng định dạng lỗi, không tự tạo ví")
    void walletDetails_withoutWallet_returns404() throws Exception {
        MvcResult result = walletDetails(userToken);

        assertThat(statusOf(result)).as(body(result)).isEqualTo(404);
        assertThat((Integer) JsonPath.read(body(result), "$.status")).isEqualTo(404);
        assertThat(jsonString(result, "$.error")).isEqualTo("Not Found");
        assertThat(jsonString(result, "$.path")).isEqualTo(WALLET_URL);
        assertThat(body(result)).doesNotContain("\"data\"");
        assertThat(walletCount()).as("đọc chi tiết ví không được tạo ví").isZero();
    }

    @Test
    @DisplayName("wallet-details sau khi mở ví => 200, đủ 6 trường, id khớp DB, currency VND, status ACTIVE")
    void walletDetails_afterOpening_returnsWalletFields() throws Exception {
        assertThat(statusOf(openWallet(userToken))).isEqualTo(200);

        MvcResult result = walletDetails(userToken);

        assertThat(statusOf(result)).as(body(result)).isEqualTo(200);
        assertThat((Integer) JsonPath.read(body(result), "$.code")).isZero();
        Map<String, Object> data = JsonPath.read(body(result), "$.data");
        assertThat(data.keySet()).containsExactlyInAnyOrder("id", "balance", "currency", "status", "createdAt",
                "updatedAt");
        assertThat(jsonString(result, "$.data.id")).isEqualTo(walletIdOf(USER_EMAIL));
        assertThat(jsonString(result, "$.data.currency")).isEqualTo("VND");
        assertThat(jsonString(result, "$.data.status")).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("Định dạng JSON: createdAt/updatedAt dạng 'yyyy-MM-dd HH:mm:ss' (không có 'T' hay múi giờ), balance là số JSON giữ đủ 4 chữ số thập phân")
    void walletDetails_jsonFormat_dateAndBalance() throws Exception {
        assertThat(statusOf(openWallet(userToken))).isEqualTo(200);

        MvcResult fresh = walletDetails(userToken);

        for (String field : List.of("createdAt", "updatedAt")) {
            String value = jsonString(fresh, "$.data." + field);
            assertThat(value).as(field).matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}");
            assertThat(LocalDateTime.parse(value, WALLET_TIME_FORMAT)).as(field + " phải parse được").isNotNull();
        }
        assertThat(body(fresh)).as("ví mới: balance là số JSON, không phải chuỗi").contains("\"balance\":0.0000");

        setBalance(USER_EMAIL, "12345678.9012");
        assertThat(body(walletDetails(userToken))).contains("\"balance\":12345678.9012");

        setBalance(USER_EMAIL, "99999999999999.9999");
        assertThat(body(walletDetails(userToken)))
                .as("không được làm tròn như số thực (double)")
                .contains("\"balance\":99999999999999.9999");
    }

    @Test
    @DisplayName("Ví bị khoá (LOCKED) vẫn xem được chi tiết với status LOCKED; open-wallet lại vẫn 409")
    void lockedWallet_isStillReadable_andCannotBeReopened() throws Exception {
        assertThat(statusOf(openWallet(userToken))).isEqualTo(200);
        jdbc.update("UPDATE wallets SET status = 'LOCKED' WHERE id = ?", walletIdOf(USER_EMAIL));

        MvcResult details = walletDetails(userToken);

        assertThat(statusOf(details)).isEqualTo(200);
        assertThat(jsonString(details, "$.data.status")).isEqualTo("LOCKED");
        assertThat(statusOf(openWallet(userToken))).isEqualTo(409);
        assertThat(walletCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Mỗi user chỉ thấy ví của chính mình (id và số dư khác nhau)")
    void walletDetails_eachUserSeesOnlyOwnWallet() throws Exception {
        createUser(OTHER_EMAIL, PASSWORD, Role.USER, 1, 1);
        String otherToken = loginOk(OTHER_EMAIL, PASSWORD).access();
        assertThat(statusOf(openWallet(userToken))).isEqualTo(200);
        assertThat(statusOf(openWallet(otherToken))).isEqualTo(200);
        setBalance(USER_EMAIL, "1000");
        setBalance(OTHER_EMAIL, "2000");

        MvcResult mine = walletDetails(userToken);
        MvcResult theirs = walletDetails(otherToken);

        assertThat(jsonString(mine, "$.data.id")).isEqualTo(walletIdOf(USER_EMAIL));
        assertThat(jsonString(theirs, "$.data.id")).isEqualTo(walletIdOf(OTHER_EMAIL));
        assertThat(body(mine)).contains("\"balance\":1000.0000");
        assertThat(body(theirs)).contains("\"balance\":2000.0000");
    }

    @Test
    @DisplayName("Admin gọi open-wallet / wallet-details: có ví riêng như user thường, không thấy hay chạm vào ví của user")
    void admin_hasOwnWallet_independentOfUsers() throws Exception {
        createUser(ADMIN_EMAIL, PASSWORD, Role.ADMIN, 1, 1);
        String adminToken = loginOk(ADMIN_EMAIL, PASSWORD).access();
        assertThat(statusOf(openWallet(userToken))).isEqualTo(200);
        setBalance(USER_EMAIL, "777000");

        MvcResult before = walletDetails(adminToken);
        assertThat(statusOf(before)).as("admin chưa mở ví thì không được thấy ví của user: " + body(before))
                .isEqualTo(404);

        MvcResult opened = openWallet(adminToken);
        assertThat(statusOf(opened)).as(body(opened)).isEqualTo(200);
        MvcResult after = walletDetails(adminToken);

        assertThat(statusOf(after)).isEqualTo(200);
        assertThat(jsonString(after, "$.data.id")).isEqualTo(walletIdOf(ADMIN_EMAIL))
                .isNotEqualTo(walletIdOf(USER_EMAIL));
        assertThat(body(after)).contains("\"balance\":0.0000");
        assertThat(statusOf(openWallet(adminToken))).as("admin mở ví lần 2").isEqualTo(409);
        assertThat(walletCount()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT balance FROM wallets WHERE id = ?", BigDecimal.class,
                walletIdOf(USER_EMAIL))).isEqualByComparingTo("777000");
    }

    @Test
    @DisplayName("Không có token => 401 cho cả hai endpoint và không tạo ví")
    void withoutToken_returns401_andCreatesNoWallet() throws Exception {
        MvcResult open = mockMvc.perform(post(OPEN_WALLET_URL)).andReturn();
        MvcResult details = mockMvc.perform(get(WALLET_URL)).andReturn();

        assertThat(statusOf(open)).isEqualTo(401);
        assertThat(statusOf(details)).isEqualTo(401);
        assertThat(walletCount()).isZero();
    }

    @Test
    @DisplayName("Sai HTTP method: GET open-wallet và POST wallet-details => 405, không tạo ví")
    void wrongHttpMethod_returns405() throws Exception {
        MvcResult getOpen = mockMvc.perform(get(OPEN_WALLET_URL).header("Authorization", "Bearer " + userToken))
                .andReturn();
        MvcResult postDetails = mockMvc.perform(post(WALLET_URL).header("Authorization", "Bearer " + userToken))
                .andReturn();

        assertThat(statusOf(getOpen)).as(body(getOpen)).isEqualTo(405);
        assertThat(statusOf(postDetails)).as(body(postDetails)).isEqualTo(405);
        assertThat(walletCount()).isZero();
    }
}
