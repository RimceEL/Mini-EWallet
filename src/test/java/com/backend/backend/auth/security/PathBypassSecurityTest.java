package com.backend.backend.auth.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import java.net.URI;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.firewall.RequestRejectedException;
import org.springframework.test.web.servlet.MvcResult;

import com.backend.backend.auth.model.Role;

public class PathBypassSecurityTest extends SecurityIntegrationTestBase {

    private static final String EMAIL = "an@gmail.com";

    private record Outcome(Integer status, String body, boolean rejectedByFirewall) {
    }

    @BeforeEach
    void seedUser() {
        createUser(EMAIL, PASSWORD, Role.USER, 1, 1);
    }

    private Outcome call(HttpMethod method, String rawUri, String accessToken) {
        try {
            var builder = request(method, URI.create(rawUri));
            if (accessToken != null) {
                builder.header("Authorization", "Bearer " + accessToken);
            }
            MvcResult result = mockMvc.perform(builder).andReturn();
            return new Outcome(statusOf(result), body(result), false);
        } catch (Exception e) {
            for (Throwable t = e; t != null; t = t.getCause()) {
                if (t instanceof RequestRejectedException) {
                    return new Outcome(null, "", true);
                }
            }
            throw new AssertionError("Request lỗi bất ngờ: " + method + " " + rawUri, e);
        }
    }

    private static void assertNeverServes(Outcome outcome, String protectedFragment, String uri) {
        if (outcome.rejectedByFirewall()) {
            return;
        }
        assertThat(outcome.status()).as("status của " + uri).isNotNull().isBetween(400, 499);
        assertThat(outcome.body()).as("body của " + uri).doesNotContain(protectedFragment);
    }

    static Stream<String> adminUrlVariants() {
        return Stream.of(
                "/api/v1/admin/ping/",
                "/api/v1/admin/ping;jsessionid=1",
                "/api/v1/admin;x=1/ping",
                "/api/v1//admin/ping",
                "/api/v1/admin//ping",
                "/api/v1/./admin/ping",
                "/api/v1/user/../admin/ping",
                "/api/v1/auth/../admin/ping",
                "/api/v1/auth/login/../../admin/ping",
                "/api/v1/%2e%2e/v1/admin/ping",
                "/api/v1/admin/%2e/ping",
                "/api/v1/admin%2fping",
                "/api/v1/admin/ping%2f",
                "/api/v1/admin/ping%00",
                "/api/v1/admin/ping%20",
                "/api/v1/admin/%70ing",
                "/api/v1/%61dmin/ping",
                "/api/v1/ADMIN/ping",
                "/API/V1/ADMIN/PING",
                "/api/v1/admin/ping.json",
                "/api/v1/admin/ping?next=/api/v1/auth/login");
    }

    static Stream<String> userUrlVariantsViaPublicPrefix() {
        return Stream.of(
                "/api/v1/auth/../user/user-details",
                "/api/v1/auth/%2e%2e/user/user-details",
                "/api/v1/auth/..;/user/user-details",
                "/api/v1/auth/x/../../user/user-details",
                "/api/v1/auth/%2e%2e%2fuser/user-details",
                "/api/v1/auth;/../user/user-details");
    }

    @ParameterizedTest(name = "ANONYMOUS {0}")
    @MethodSource("adminUrlVariants")
    @DisplayName("Biến tấu URL của API admin: người CHƯA đăng nhập không bao giờ nhận được nội dung admin")
    void adminUrlVariants_neverServeAnonymous(String uri) {
        assertNeverServes(call(HttpMethod.GET, uri, null), "pong", uri);
    }

    @ParameterizedTest(name = "USER {0}")
    @MethodSource("adminUrlVariants")
    @DisplayName("Biến tấu URL của API admin: USER thường không bao giờ nhận được nội dung admin")
    void adminUrlVariants_neverServeUserToken(String uri) throws Exception {
        Tokens user = loginOk(EMAIL, PASSWORD);

        assertNeverServes(call(HttpMethod.GET, uri, user.access()), "pong", uri);
    }

    @ParameterizedTest(name = "ANONYMOUS {0}")
    @MethodSource("userUrlVariantsViaPublicPrefix")
    @DisplayName("Mượn tiền tố công khai /api/v1/auth/ để đi vòng sang API user: không lộ dữ liệu user")
    void publicPrefixTricks_cannotReachUserEndpoint(String uri) {
        assertNeverServes(call(HttpMethod.GET, uri, null), EMAIL, uri);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = { "HEAD", "POST", "PUT", "DELETE", "PATCH", "OPTIONS" })
    @DisplayName("Đổi HTTP method không né được phân quyền: ANONYMOUS luôn 401")
    void anyMethodOnAdminUrl_isUnauthorizedForAnonymous(String method) {
        Outcome outcome = call(HttpMethod.valueOf(method), ADMIN_URL, null);

        assertThat(outcome.rejectedByFirewall()).isFalse();
        assertThat(outcome.status()).as(method + " " + ADMIN_URL).isEqualTo(401);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = { "HEAD", "POST", "PUT", "DELETE", "PATCH", "OPTIONS" })
    @DisplayName("Đổi HTTP method không né được phân quyền: USER luôn 403")
    void anyMethodOnAdminUrl_isForbiddenForUser(String method) throws Exception {
        Tokens user = loginOk(EMAIL, PASSWORD);

        Outcome outcome = call(HttpMethod.valueOf(method), ADMIN_URL, user.access());

        assertThat(outcome.rejectedByFirewall()).isFalse();
        assertThat(outcome.status()).as(method + " " + ADMIN_URL).isEqualTo(403);
    }

    @Test
    @DisplayName("Mọi HTTP method TRACE không thể xuyên qua Firewall: luôn trả 400")
    void traceMethodIsRejectedByFirewall() throws Exception {
        Outcome outcome = call(HttpMethod.TRACE, "/any-fake-url", null);
        assertThat(outcome.rejectedByFirewall()).isFalse();
        assertThat(outcome.status()).as(HttpMethod.TRACE.toString() + " " + "/any-fake-url").isEqualTo(400);
    }
}