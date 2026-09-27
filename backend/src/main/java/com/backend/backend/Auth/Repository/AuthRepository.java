package com.backend.backend.Auth.Repository;

import java.util.List;
import java.util.Optional;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.backend.backend.Auth.Model.User;
import com.backend.backend.DTO.RegisterRequest;

import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class AuthRepository {
    private final JdbcTemplate jdbcTemplate;
    private final RowMapper<User> rowMapper = new BeanPropertyRowMapper<>(User.class);

    public Optional<User> findUserByEmail(String email) throws DataAccessException {
        String sql = """
                SELECT * FROM users
                WHERE email = ?
                """;
        List<User> result = jdbcTemplate.query(sql, rowMapper, email);
        return result.stream().findFirst();
    }

    public void register(RegisterRequest user) throws DataAccessException {
        String sql = """
                INSERT INTO users (email, username, password, full_name)
                VALUES(?,?,?,?)
                """;
        jdbcTemplate.update(sql, user.getEmail(), user.getUsername(), user.getPassword(),
                user.getFullName());
    }

    public void markVerified(String email) {
        String sql = "UPDATE users SET is_verified = 1 WHERE email = ?";
        jdbcTemplate.update(sql, email);
    }
}
