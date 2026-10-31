package com.backend.backend.User.DTO;

import com.backend.backend.Auth.Model.Role;

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
