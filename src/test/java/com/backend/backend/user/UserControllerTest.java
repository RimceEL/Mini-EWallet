package com.backend.backend.user;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.backend.backend.auth.filter.JwtAuthenticationFilter;
import com.backend.backend.auth.model.Role;
import com.backend.backend.auth.model.User;
import com.backend.backend.auth.service.MyUserDetailsService;

@WebMvcTest(UserController.class)
@AutoConfigureMockMvc(addFilters = false)
public class UserControllerTest {

    private static final String URL = "/api/v1/user/user-details";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @MockitoBean
    private MyUserDetailsService myUserDetailsService;

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static void authenticateAs(User user) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null,
                        user.getRole() != null ? user.getAuthorities() : List.of()));
    }

    @Test
    @DisplayName("[GIẢ THUYẾT] getUserDetails: username trả về phải là tên hiển thị (user.getName()), KHÔNG phải email")
    void getUserDetails_returnsAllProfileFieldsWithRealUsername() throws Exception {
        User user = User.builder()
                .id("user-123")
                .email("an@gmail.com")
                .username("nguoidung01")
                .password("$2a$12$hiddenHash")
                .fullName("Nguyen Van An")
                .phoneNumber("0901234567")
                .address("123 Nguyen Trai")
                .role(Role.USER)
                .isActive(1)
                .isVerified(1)
                .build();
        authenticateAs(user);

        mockMvc.perform(get(URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("Success"))
                .andExpect(jsonPath("$.data.id").value("user-123"))
                .andExpect(jsonPath("$.data.email").value("an@gmail.com"))
                .andExpect(jsonPath("$.data.username").value("nguoidung01"))
                .andExpect(jsonPath("$.data.fullName").value("Nguyen Van An"))
                .andExpect(jsonPath("$.data.phoneNumber").value("0901234567"))
                .andExpect(jsonPath("$.data.address").value("123 Nguyen Trai"))
                .andExpect(jsonPath("$.data.role").value("USER"));
    }

    @Test
    @DisplayName("getUserDetails: các trường tuỳ chọn null (phoneNumber, address, fullName) không gây lỗi")
    void getUserDetails_withNullOptionalFields_returns200() throws Exception {
        User user = User.builder()
                .id("user-456")
                .email("min@gmail.com")
                .username("minuser01")
                .role(Role.ADMIN)
                .isActive(1)
                .isVerified(1)
                .build();
        authenticateAs(user);

        mockMvc.perform(get(URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value("user-456"))
                .andExpect(jsonPath("$.data.email").value("min@gmail.com"))
                .andExpect(jsonPath("$.data.role").value("ADMIN"))
                .andExpect(jsonPath("$.data.phoneNumber").doesNotExist())
                .andExpect(jsonPath("$.data.address").doesNotExist());
    }

    @Test
    @DisplayName("getUserDetails: sai HTTP method (POST/PUT/DELETE) => 405 Method Not Allowed")
    void getUserDetails_wrongHttpMethods_return405() throws Exception {
        User user = User.builder().id("u1").email("a@b.com").role(Role.USER).build();
        authenticateAs(user);

        mockMvc.perform(post(URL)).andExpect(status().isMethodNotAllowed());
        mockMvc.perform(put(URL)).andExpect(status().isMethodNotAllowed());
        mockMvc.perform(delete(URL)).andExpect(status().isMethodNotAllowed());
    }
}
