package com.backend.backend.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import com.backend.backend.auth.model.Role;
import com.backend.backend.auth.model.User;
import com.backend.backend.auth.repository.AuthRepository;

@ExtendWith(MockitoExtension.class)
public class MyUserDetailsServiceTest {

    @Mock
    private AuthRepository authRepository;

    @InjectMocks
    private MyUserDetailsService service;

    @Test
    @DisplayName("loadUserByUsername: tìm thấy email => trả về đúng UserDetails từ repository")
    void loadUserByUsername_whenFound_returnsUser() {
        User user = User.builder()
                .id("u-1")
                .email("an@gmail.com")
                .password("$2a$12$hash")
                .role(Role.USER)
                .isActive(1)
                .isVerified(1)
                .build();
        when(authRepository.findUserByEmail("an@gmail.com")).thenReturn(Optional.of(user));

        UserDetails loaded = service.loadUserByUsername("an@gmail.com");

        assertThat(loaded).isSameAs(user);
        verify(authRepository).findUserByEmail("an@gmail.com");
    }

    @Test
    @DisplayName("loadUserByUsername: không tìm thấy => ném UsernameNotFoundException kèm email")
    void loadUserByUsername_whenMissing_throwsUsernameNotFound() {
        when(authRepository.findUserByEmail("none@gmail.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.loadUserByUsername("none@gmail.com"))
                .isInstanceOf(UsernameNotFoundException.class)
                .hasMessage("Not found email: none@gmail.com");
    }
}
