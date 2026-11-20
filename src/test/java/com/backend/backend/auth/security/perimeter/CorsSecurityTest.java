package com.backend.backend.auth.security.perimeter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.backend.backend.auth.config.SecurityConfig;
import com.backend.backend.auth.customexception.CustomAccessDeniedHandler;
import com.backend.backend.auth.entrypoint.JwtAuthenticationEntryPoint;
import com.backend.backend.auth.model.Role;
import com.backend.backend.auth.model.User;
import com.backend.backend.auth.service.JwtService;
import com.backend.backend.auth.service.MyUserDetailsService;
import com.backend.backend.auth.service.RefreshTokenService;
import com.backend.backend.user.UserController;

@WebMvcTest(controllers = UserController.class)
@Import({
                SecurityConfig.class,
                JwtAuthenticationEntryPoint.class,
                CustomAccessDeniedHandler.class,
                JwtService.class })
@TestPropertySource(properties = {
                "jwt.secret=" + CorsSecurityTest.SECRET,
                "jwt.access.expiration=60000",
                "jwt.refresh.expiration=600000",
                "password.strength=4"
})
public class CorsSecurityTest {

        static final String SECRET = "cors-test-secret-cors-test-secret-12345";

        private static final String USER_URL = "/api/v1/user/user-details";
        private static final String LOGIN_URL = "/api/v1/auth/login";
        private static final String ALLOWED_ORIGIN = "http://localhost:5173";
        private static final String EMAIL = "an@gmail.com";
        private static final String JTI = "jti-1";

        @Autowired
        private MockMvc mockMvc;

        @Autowired
        private JwtService jwtService;

        @MockitoBean
        private MyUserDetailsService userDetailsService;

        @MockitoBean
        private RefreshTokenService refreshTokenService;

        private String bearerOfValidUser() {
                User user = User.builder()
                                .id("user-1")
                                .email(EMAIL)
                                .username("tester01")
                                .password("khong-dung-toi")
                                .role(Role.USER)
                                .fullName("Nguyen Van An")
                                .isActive(1)
                                .isVerified(1)
                                .build();
                when(userDetailsService.loadUserByUsername(EMAIL)).thenReturn(user);
                when(refreshTokenService.isExist(EMAIL, JTI)).thenReturn(true);
                return "Bearer " + jwtService.accessTokenGenerate(user, JTI);
        }

        @Test
        @DisplayName("Preflight từ frontend hợp lệ => 200, trả đúng origin đó (không phải '*'), cho phép credentials")
        void preflight_fromAllowedOrigin_isAccepted() throws Exception {
                MvcResult result = mockMvc.perform(options(USER_URL)
                                .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Authorization"))
                                .andExpect(status().isOk())
                                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED_ORIGIN))
                                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"))
                                .andReturn();

                var response = result.getResponse();
                assertThat(response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS)).contains("GET");
                assertThat(response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS))
                                .contains("Authorization").doesNotContain("*");
                assertThat(response.getHeaders(HttpHeaders.VARY)).anyMatch(v -> v.contains("Origin"));
        }

        @Test
        @DisplayName("Preflight không mang token vẫn không bị chặn bởi xác thực (trình duyệt không gửi token ở preflight)")
        void preflight_doesNotRequireAuthentication() throws Exception {
                mockMvc.perform(options(LOGIN_URL)
                                .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Content-Type"))
                                .andExpect(status().isOk())
                                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED_ORIGIN));
        }

        @ParameterizedTest(name = "origin = {0}")
        @ValueSource(strings = {
                        "http://evil.example",
                        "http://localhost:5173.evil.example",
                        "http://evil.example/http://localhost:5173",
                        "https://localhost:5173",
                        "http://localhost:5174",
                        "null" })
        @DisplayName("Preflight từ origin lạ hoặc giả dạng origin hợp lệ => 403, không có header cho phép")
        void preflight_fromForeignOrigin_isRejected(String origin) throws Exception {
                mockMvc.perform(options(USER_URL)
                                .header(HttpHeaders.ORIGIN, origin)
                                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Authorization"))
                                .andExpect(status().isForbidden())
                                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN))
                                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS));
        }

        @ParameterizedTest(name = "method = {0}")
        @ValueSource(strings = { "TRACE", "HEAD" })
        @DisplayName("Preflight xin dùng method không nằm trong danh sách cho phép => 403")
        void preflight_withDisallowedMethod_isRejected(String method) throws Exception {
                mockMvc.perform(options(USER_URL)
                                .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, method))
                                .andExpect(status().isForbidden())
                                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
        }

        @ParameterizedTest(name = "header = {0}")
        @ValueSource(strings = { "X-Evil-Header", "X-Requested-With" })
        @DisplayName("Preflight chỉ xin header ngoài danh sách => 403")
        void preflight_withOnlyDisallowedHeaders_isRejected(String requestedHeaders) throws Exception {
                mockMvc.perform(options(USER_URL)
                                .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, requestedHeaders))
                                .andExpect(status().isForbidden())
                                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
        }

        @Test
        @DisplayName("Preflight xin lẫn header hợp lệ và không hợp lệ => chỉ trả về header hợp lệ")
        void preflight_withMixedHeaders_onlyAllowsValidOnes() throws Exception {
                mockMvc.perform(options(USER_URL)
                                .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Authorization, X-Evil-Header"))
                                .andExpect(status().isOk())
                                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, "Authorization"));
        }

        @Test
        @DisplayName("Request thật từ origin lạ, kể cả có token hợp lệ => 403, không lộ dữ liệu và không có header CORS")
        void actualRequest_fromForeignOrigin_isRejectedEvenWithValidToken() throws Exception {
                MvcResult result = mockMvc.perform(get(USER_URL)
                                .header(HttpHeaders.ORIGIN, "http://evil.example")
                                .header(HttpHeaders.AUTHORIZATION, bearerOfValidUser()))
                                .andExpect(status().isForbidden())
                                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN))
                                .andReturn();

                assertThat(result.getResponse().getContentAsString()).doesNotContain(EMAIL);
        }

        @Test
        @DisplayName("Request thật từ frontend hợp lệ với token đúng => 200 và có header CORS đúng origin")
        void actualRequest_fromAllowedOrigin_withValidToken_isServed() throws Exception {
                mockMvc.perform(get(USER_URL)
                                .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                                .header(HttpHeaders.AUTHORIZATION, bearerOfValidUser()))
                                .andExpect(status().isOk())
                                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED_ORIGIN))
                                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"));
        }

        @Test
        @DisplayName("Lỗi 401 gửi tới frontend hợp lệ vẫn có header CORS (để frontend đọc được lỗi và đi refresh token)")
        void unauthorizedResponse_toAllowedOrigin_stillCarriesCorsHeaders() throws Exception {
                mockMvc.perform(get(USER_URL).header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN))
                                .andExpect(status().isUnauthorized())
                                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED_ORIGIN));
        }

        @Test
        @DisplayName("Request không có header Origin (gọi trực tiếp/server-to-server) không bị gắn header CORS")
        void requestWithoutOrigin_hasNoCorsHeaders() throws Exception {
                mockMvc.perform(get(USER_URL).header(HttpHeaders.AUTHORIZATION, bearerOfValidUser()))
                                .andExpect(status().isOk())
                                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN))
                                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS));
        }
}
