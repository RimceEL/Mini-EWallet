package com.backend.backend.Wallet;

import java.util.Optional;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class WalletRepository {
    private final JdbcTemplate jdbcTemplate;
    private final RowMapper<Wallet> wallRowMapper = new BeanPropertyRowMapper<>(Wallet.class);

    public Optional<Wallet> findWalletByUserEmail(String email) throws DataAccessException {
        String sql = """
                SELECT w.*
                FROM wallets w
                INNER JOIN users u ON w.user_id = u.id
                WHERE u.email = ?
                """;
        return jdbcTemplate.query(sql, wallRowMapper, email).stream().findFirst();
    }

    public void createWalletByUserEmail(String userEmail) throws DataAccessException {
        String sql = """
                INSERT INTO wallets (user_id)
                SELECT id FROM users
                WHERE email = ?
                """;
        jdbcTemplate.update(sql, userEmail);
    }
}
