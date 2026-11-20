package com.backend.backend.auth.security.perimeter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
        JwtService.class,
        SessionAndHeadersSecurityTest.AdminPingController.class })
@TestPropertySource(properties = {
        "jwt.secret=" + SessionAndHeadersSecurityTest.SECRET,
        "jwt.access.expiration=60000",
        "jwt.refresh.expiration=600000",
        "password.strength=4"
})
public class SessionAndHeadersSecurityTest {

    static final String SECRET = "session-test-secret-session-test-secret-12345";

    private static final String USER_URL = "/api/v1/user/user-details";
    private static final String ADMIN_URL = "/api/v1/admin/ping";
    private static final String EMAIL = "an@gmail.com";
    private static final String JTI = "jti-1";

    @RestController
    @RequestMapping("/api/v1/admin")
    public static class AdminPingController {
        @GetMapping("/ping")
        public String ping() {
            return "pong";
        }
    }

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

    private static void assertNoSessionAndNoCookie(MvcResult result) {
        assertThat(result.getResponse().getHeaders("Set-Cookie")).as("header Set-Cookie").isEmpty();
        assertThat(result.getResponse().getCookies()).as("cookie").isEmpty();
        assertThat(result.getRequest().getSession(false)).as("HttpSession").isNull();
    }

    @Test
    @DisplayName("Request thành công (200) không tạo session và không đặt cookie")
    void successfulRequest_createsNoSessionAndNoCookie() throws Exception {
        MvcResult result = mockMvc.perform(get(USER_URL).header("Authorization", bearerOfValidUser())).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertNoSessionAndNoCookie(result);
    }

    @Test
    @DisplayName("Request không có token (401) không tạo session và không đặt cookie")
    void unauthenticatedRequest_createsNoSessionAndNoCookie() throws Exception {
        MvcResult result = mockMvc.perform(get(USER_URL)).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(401);
        assertNoSessionAndNoCookie(result);
    }

    @Test
    @DisplayName("Token rác (401) không tạo session và không đặt cookie")
    void garbageToken_createsNoSessionAndNoCookie() throws Exception {
        MvcResult result = mockMvc.perform(get(USER_URL).header("Authorization", "Bearer rac")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(401);
        assertNoSessionAndNoCookie(result);
    }

    @Test
    @DisplayName("USER gọi API admin (403) không tạo session và không đặt cookie")
    void forbiddenRequest_createsNoSessionAndNoCookie() throws Exception {
        MvcResult result = mockMvc.perform(get(ADMIN_URL).header("Authorization", bearerOfValidUser())).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertNoSessionAndNoCookie(result);
    }

    @Test
    @DisplayName("Cookie JSESSIONID gửi lên không thay thế được token: vẫn 401")
    void sessionCookie_isNotAnAuthenticationMechanism() throws Exception {
        MvcResult result = mockMvc.perform(get(USER_URL)
                .cookie(new jakarta.servlet.http.Cookie("JSESSIONID", "ABCDEF1234567890")))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(401);
        assertNoSessionAndNoCookie(result);
    }

    @Test
    @DisplayName("Phản hồi chứa dữ liệu người dùng có header chống cache và chống đoán MIME")
    void successResponse_hasAntiCachingAndHardeningHeaders() throws Exception {
        MvcResult result = mockMvc.perform(get(USER_URL).header("Authorization", bearerOfValidUser())).andReturn();

        var response = result.getResponse();
        assertThat(response.getHeader("Cache-Control")).as("Cache-Control").contains("no-store");
        assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.getHeader("X-Frame-Options")).isEqualTo("DENY");
    }

    @Test
    @DisplayName("Phản hồi 401 cũng có header chống cache và chống đoán MIME")
    void unauthorizedResponse_hasAntiCachingAndHardeningHeaders() throws Exception {
        MvcResult result = mockMvc.perform(get(USER_URL)).andReturn();

        var response = result.getResponse();
        assertThat(response.getHeader("Cache-Control")).as("Cache-Control").contains("no-store");
        assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
    }

    @Test
    @DisplayName("401 không kèm WWW-Authenticate: Basic (tránh trình duyệt bật hộp thoại đăng nhập Basic)")
    void unauthorizedResponse_doesNotAskForBasicAuth() throws Exception {
        MvcResult result = mockMvc.perform(get(USER_URL)).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(401);
        assertThat(result.getResponse().getHeader("WWW-Authenticate")).isNull();
    }
}