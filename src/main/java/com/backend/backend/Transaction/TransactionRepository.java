package com.backend.backend.Transaction;

import java.math.BigDecimal;
import java.util.Optional;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class TransactionRepository {
    private final JdbcTemplate jdbcTemplate;
    private static final RowMapper<Transaction> transactionRowMapper = new BeanPropertyRowMapper<>(Transaction.class);

    public Optional<Transaction> findTransactionByRefId(String referenceId) {
        String sql = """
                SELECT * FROM transactions
                WHERE reference_id = ?
                """;
        return jdbcTemplate.query(sql, transactionRowMapper, referenceId).stream().findFirst();
    }

    public boolean isUserWallet(String walletId, String email) {
        String sql = """
                SELECT COUNT(*) FROM wallets w JOIN users u ON w.user_id = u.id
                WHERE w.id = ? AND u.email = ?
                """;
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, walletId, email);
        return count != null && count > 0;
    }

    public boolean credit(String walletId, BigDecimal amount) throws DataAccessException {
        String sql = """
                UPDATE wallets w
                SET w.balance = w.balance + ?, w.version = w.version + 1
                WHERE w.id = ? AND w.status = 'ACTIVE'
                """;
        return jdbcTemplate.update(sql, amount, walletId) > 0;
    }

    public boolean debit(String walletId, BigDecimal amount) {
        String sql = """
                UPDATE wallets w
                SET w.balance = w.balance - ?, w.version = w.version + 1
                WHERE w.id = ? AND w.balance >= ? AND w.status = 'ACTIVE'
                """;
        return jdbcTemplate.update(sql, amount, walletId, amount) > 0;
    }

    public boolean transfer(String sourceWalletId, String desWalletId, BigDecimal amount) throws DataAccessException {
        boolean resDebit;
        boolean resCredit;
        if (sourceWalletId.compareTo(desWalletId) < 0) {
            resDebit = debit(sourceWalletId, amount);
            resCredit = credit(desWalletId, amount);
        } else {
            resCredit = credit(desWalletId, amount);
            resDebit = debit(sourceWalletId, amount);
        }
        return resDebit && resCredit;
    }

    public boolean transactionRecord(Transaction transaction) throws DataAccessException {
        String sql = """
                INSERT INTO transactions
                    (reference_id, type, status, source_wallet_id, destination_wallet_id, amount, description)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """;

        return jdbcTemplate.update(sql,
                transaction.getReferenceId(),
                transaction.getType().name(),
                TransactionStatus.PENDING.name(),
                transaction.getSourceWalletId(),
                transaction.getDestinationWalletId(),
                transaction.getAmount(),
                transaction.getDescription()) > 0;
    }

    public boolean updateStatus(String referenceId, TransactionStatus newStatus) throws DataAccessException {
        String sql = """
                UPDATE transactions
                SET status = ?
                WHERE reference_id = ? AND status = 'PENDING'
                """;

        return jdbcTemplate.update(sql, newStatus.name(), referenceId) > 0;
    }

    public boolean isActiveWallet(String walletId) {
        Integer c = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM wallets WHERE id = ? AND status = 'ACTIVE'", Integer.class, walletId);
        return c != null && c > 0;
    }

    public boolean canDebit(String walletId, BigDecimal amount) {
        Integer c = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM wallets WHERE id = ? AND status = 'ACTIVE' AND balance >= ?",
                Integer.class, walletId, amount);
        return c != null && c > 0;
    }

    public int expirePending(int minutes) {
        String sql = """
                UPDATE transactions
                SET status = 'FAILED'
                WHERE status = 'PENDING' AND created_at < NOW() - INTERVAL ? MINUTE
                """;
        return jdbcTemplate.update(sql, minutes);
    }

}
