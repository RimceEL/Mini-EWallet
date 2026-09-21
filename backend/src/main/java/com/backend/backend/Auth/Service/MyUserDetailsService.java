package com.backend.backend.Auth.Service;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Component;

import com.backend.backend.Auth.Repository.AuthRepository;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class MyUserDetailsService implements UserDetailsService {

    private final AuthRepository authRepository;

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        return authRepository
                .findUserByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("Not found email: " + email));
    }

}
