package com.backend.backend.auth.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.Collection;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.core.GrantedAuthority;

public class UserTest {

    @Test
    @DisplayName("getUsername() trả về email (định danh Spring Security), getName() trả về username hiển thị")
    void getUsername_returnsEmail_whileGetName_returnsDisplayName() {
        User user = User.builder()
                .email("an@gmail.com")
                .username("nguoidung01")
                .build();

        assertThat(user.getUsername()).isEqualTo("an@gmail.com");
        assertThat(user.getName()).isEqualTo("nguoidung01");
    }

    @ParameterizedTest(name = "role = {0}")
    @EnumSource(Role.class)
    @DisplayName("getAuthorities: map đúng sang ROLE_<NAME>")
    void getAuthorities_mapsRoleWithPrefix(Role role) {
        User user = User.builder().role(role).build();

        Collection<? extends GrantedAuthority> authorities = user.getAuthorities();

        assertThat(authorities)
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_" + role.name());
    }

    @Test
    @DisplayName("[GIẢ THUYẾT] getAuthorities: role = null không được ném NullPointerException")
    void getAuthorities_nullRole_shouldReturnEmptyWithoutThrowing() {
        User user = User.builder().email("an@gmail.com").build();

        assertThatCode(() -> {
            Collection<? extends GrantedAuthority> authorities = user.getAuthorities();
            assertThat(authorities).isEmpty();
        }).doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "isActive={0} -> nonLocked={1}")
    @CsvSource({ "1,true", "2,true", "0,false", "-1,false" })
    @DisplayName("isAccountNonLocked: chỉ trả true khi isActive >= 1")
    void isAccountNonLocked_checksActiveFlag(int isActive, boolean expected) {
        User user = User.builder().isActive(isActive).build();

        assertThat(user.isAccountNonLocked()).isEqualTo(expected);
    }

    @ParameterizedTest(name = "isVerified={0} -> enabled={1}")
    @CsvSource({ "1,true", "2,true", "0,false", "-1,false" })
    @DisplayName("isEnabled: chỉ trả true khi isVerified >= 1")
    void isEnabled_checksVerifiedFlag(int isVerified, boolean expected) {
        User user = User.builder().isVerified(isVerified).build();

        assertThat(user.isEnabled()).isEqualTo(expected);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(ints = { 0 })
    @DisplayName("User mới tạo / null flag: isAccountNonLocked và isEnabled trả false, không ném NPE")
    void nullFlags_returnFalseSafely(Integer flag) {
        User user = User.builder().isActive(flag).isVerified(flag).build();

        assertThat(user.isAccountNonLocked()).isFalse();
        assertThat(user.isEnabled()).isFalse();
        assertThat(user.isAccountNonExpired()).isTrue();
        assertThat(user.isCredentialsNonExpired()).isTrue();
    }

    @Test
    @DisplayName("toString(): không được rò rỉ password hoặc hash BCrypt (nhờ @ToString.Exclude)")
    void toString_excludesPassword() {
        String secretHash = "$2a$12$SuperSecretHashValueShouldNeverBeLogged123456";
        User user = User.builder()
                .id("u-1")
                .email("an@gmail.com")
                .username("nguoidung01")
                .password(secretHash)
                .role(Role.USER)
                .build();

        String dumped = user.toString();

        assertThat(dumped).contains("an@gmail.com");
        assertThat(dumped).doesNotContain(secretHash).doesNotContain("password=");
    }
}
