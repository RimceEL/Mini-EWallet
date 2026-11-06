package com.backend.backend.user.dto;

import com.backend.backend.auth.model.Role;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserDetailResponse {
    private String id;
    private String email;
    private String username;
    private String fullName;
    private String phoneNumber;
    private String address;
    private Role role;
}
