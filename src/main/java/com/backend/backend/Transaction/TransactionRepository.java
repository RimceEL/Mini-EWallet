package com.backend.backend.transaction;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

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

    public boolean increasePending(String walletId) throws DataAccessException {
        String sql = """
                    UPDATE wallets
                    SET pending = pending + 1
                    WHERE pending < 5 AND id = ?
                """;
        return jdbcTemplate.update(sql, walletId) > 0;
    }

    public boolean decreasePending(String walletId) throws DataAccessException {
        String sql = """
                    UPDATE wallets
                    SET pending = pending - 1
                    WHERE pending > 0 AND id = ?
                """;
        return jdbcTemplate.update(sql, walletId) > 0;
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

    @Transactional
    public int expirePending(int minutes) {
        List<ExpiredTx> expired = lockExpiredPending(minutes);
        Map<String, Integer> perWallet = new TreeMap<>();
        for (ExpiredTx tx : expired) {
            if (markFailed(tx.id())) {
                perWallet.merge(tx.walletId(), 1, Integer::sum);
            }
        }

        perWallet.forEach((walletId, count) -> decreasePendingBy(walletId, count));
        return expired.size();
    }

    public record ExpiredTx(String id, String walletId) {
    }

    public List<ExpiredTx> lockExpiredPending(int minutes) {
        String sql = """
                SELECT id, source_wallet_id
                FROM transactions
                WHERE status = 'PENDING'
                  AND created_at < NOW() - INTERVAL ? MINUTE
                ORDER BY created_at
                FOR UPDATE SKIP LOCKED
                """;
        return jdbcTemplate.query(sql,
                (rs, i) -> new ExpiredTx(rs.getString("id"), rs.getString("source_wallet_id")),
                minutes);
    }

    public boolean markFailed(String txnId) {
        String sql = "UPDATE transactions SET status = 'FAILED' WHERE id = ? AND status = 'PENDING'";
        return jdbcTemplate.update(sql, txnId) > 0;
    }

    public void decreasePendingBy(String walletId, int n) {
        String sql = "UPDATE wallets SET pending = GREATEST(pending - ?, 0) WHERE id = ?";
        jdbcTemplate.update(sql, n, walletId);
    }

}
