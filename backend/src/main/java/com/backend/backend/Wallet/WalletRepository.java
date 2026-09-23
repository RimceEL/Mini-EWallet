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

    public Optional<Wallet> findWalletByUserId(String userId) throws DataAccessException {
        String sql = """
                SELECT * FROM wallets
                WHERE user_id = ?
                """;
        return jdbcTemplate.query(sql, wallRowMapper, userId).stream().findFirst();
    }

    public void createWalletByUserId(String userId) throws DataAccessException {
        String sql = """
                INSERT INTO wallets (user_id)
                VALUES (?)
                """;
        jdbcTemplate.update(sql, userId);
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
