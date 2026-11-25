package com.backend.backend.global;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import com.backend.backend.auth.model.Role;
import com.backend.backend.auth.security.SecurityIntegrationTestBase;
import com.jayway.jsonpath.JsonPath;

public class ApiContractConsistencyTest extends SecurityIntegrationTestBase {

        private static final DateTimeFormatter ERROR_TIME = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss");

        private static void assertStandardErrorContract(MvcResult result, int expectedStatus, String expectedPath)
                        throws Exception {
                String raw = body(result);
                assertThat(statusOf(result)).as(raw).isEqualTo(expectedStatus);
                assertThat((Integer) JsonPath.read(raw, "$.status")).isEqualTo(expectedStatus);
                assertThat(jsonString(result, "$.error")).isNotBlank();
                assertThat(jsonString(result, "$.message")).isNotBlank();
                assertThat(jsonString(result, "$.path")).isEqualTo(expectedPath);
                String ts = jsonString(result, "$.timestamp");
                assertThat(LocalDateTime.parse(ts, ERROR_TIME)).isNotNull();
        }

        @Test
        @DisplayName("Lỗi từ Controller/GlobalExceptionHandler (400/401/404/409) tuân thủ đủ 5 trường ErrorResponse")
        void controllerErrors_followStandardErrorResponseContract() throws Exception {
                createUser("an@gmail.com", PASSWORD, Role.USER, 1, 1);
                Tokens tokens = loginOk("an@gmail.com", PASSWORD);

                assertStandardErrorContract(
                                loginRaw("an@gmail.com", "SaiMatKhau!12345"), 401, "/api/v1/auth/login");
                assertStandardErrorContract(
                                getWithToken("/api/v1/user/wallet-details", tokens.access()), 404,
                                "/api/v1/user/wallet-details");
                assertStandardErrorContract(
                                postJson("/api/v1/auth/register",
                                                "{\"email\":\"an@gmail.com\",\"username\":\"nguoidung01\",\"password\":\""
                                                                + PASSWORD
                                                                + "\",\"fullName\":\"Nguyen Van An\"}"),
                                409, "/api/v1/auth/register");
        }

        @Test
        @Disabled
        @DisplayName("[GIẢ THUYẾT] Lỗi 401/403 từ Security Filter (EntryPoint & AccessDeniedHandler) cũng phải đồng bộ cấu trúc ErrorResponse (có timestamp, path, status)")
        void securityHandlers_matchStandardErrorResponseContract() throws Exception {
                createUser("an@gmail.com", PASSWORD, Role.USER, 1, 1);
                Tokens tokens = loginOk("an@gmail.com", PASSWORD);

                MvcResult unauth = mockMvc.perform(get(USER_URL)).andReturn();
                MvcResult forbidden = getWithToken(ADMIN_URL, tokens.access());

                assertStandardErrorContract(unauth, 401, USER_URL);
                assertStandardErrorContract(forbidden, 403, ADMIN_URL);
        }
}
