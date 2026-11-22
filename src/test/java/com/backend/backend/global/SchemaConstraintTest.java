package com.backend.backend.global;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import com.backend.backend.transaction.MySqlIntegrationTestBase;
import com.zaxxer.hikari.HikariDataSource;

@SpringJUnitConfig(SchemaConstraintTest.Config.class)
@Timeout(value = 120, unit = TimeUnit.SECONDS)
public class SchemaConstraintTest extends MySqlIntegrationTestBase {

    private static final int MYSQL_CHECK_CONSTRAINT_VIOLATED = 3819;
    private static final int MYSQL_BAD_NULL_ERROR = 1048;

    @TestConfiguration(proxyBeanMethods = false)
    static class Config {

        @Bean(destroyMethod = "close")
        HikariDataSource dataSource() {
            return newDataSource(10, 10, 30000);
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }
    }

    @Autowired
    private DataSource dataSource;

    private String walletA;
    private String walletB;

    @BeforeEach
    void setUp() {

        walletA = seedWallet("a@test.com");
        walletB = seedWallet("b@test.com");
    }

    private String seedUser(String email) {
        jdbc.update("INSERT INTO users (email, password) VALUES (?, ?)", email, "hash");
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", String.class, email);
    }

    private String seedWallet(String email) {
        String userId = seedUser(email);
        String walletId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO wallets (id, user_id, balance, status, pending) VALUES (?, ?, 1000, 'ACTIVE', 0)",
                walletId, userId);
        return walletId;
    }

    private static SQLException findSqlException(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof SQLException sqlEx) {
                return sqlEx;
            }
        }
        return null;
    }

    private void assertCheckViolation(ThrowingCallable action, String... expectedConstraintNames) {
        assertThatThrownBy(action)
                .isInstanceOf(DataAccessException.class)
                .satisfies(ex -> {
                    SQLException sqlEx = findSqlException(ex);
                    assertThat((Object) sqlEx).as("phải có nguyên nhân SQLException").isNotNull();
                    assertThat(sqlEx.getErrorCode())
                            .as("MySQL CHECK constraint phải trả error code 3819")
                            .isEqualTo(MYSQL_CHECK_CONSTRAINT_VIOLATED);
                    assertThat(sqlEx.getMessage()).containsAnyOf(expectedConstraintNames);
                });
    }

    @Test
    @DisplayName("Migration V2: cột version và constraint chk_wallet_version đã bị xoá khỏi bảng wallets")
    void migrationV2_dropsVersionColumnAndCheck() {
        Integer colCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = 'wallets' AND column_name = 'version'",
                Integer.class);
        Integer chkCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.table_constraints "
                        + "WHERE table_schema = DATABASE() AND table_name = 'wallets' AND constraint_name = 'chk_wallet_version'",
                Integer.class);

        assertThat(colCount).isZero();
        assertThat(chkCount).isZero();
    }

    @Test
    @DisplayName("CHECK wallets: số dư âm bị từ chối (chk_wallet_balance)")
    void wallet_negativeBalance_isRejected() {
        String userId = seedUser("neg@test.com");

        assertCheckViolation(() -> jdbc.update(
                "INSERT INTO wallets (id, user_id, balance) VALUES (?, ?, -1)",
                UUID.randomUUID().toString(), userId),
                "chk_wallet_balance");
    }

    @Test
    @DisplayName("CHECK wallets: currency không hợp lệ (EUR) bị từ chối (chk_wallet_currency)")
    void wallet_invalidCurrency_isRejected() {
        String userId = seedUser("eur@test.com");

        assertCheckViolation(() -> jdbc.update(
                "INSERT INTO wallets (id, user_id, currency) VALUES (?, ?, 'EUR')",
                UUID.randomUUID().toString(), userId),
                "chk_wallet_currency");
    }

    @Test
    @DisplayName("CHECK wallets: status không hợp lệ (XYZ) bị từ chối (chk_wallet_status)")
    void wallet_invalidStatus_isRejected() {
        String userId = seedUser("xyz@test.com");

        assertCheckViolation(() -> jdbc.update(
                "INSERT INTO wallets (id, user_id, status) VALUES (?, ?, 'XYZ')",
                UUID.randomUUID().toString(), userId),
                "chk_wallet_status");
    }

    @Test
    @DisplayName("CHECK wallets: hệ thống chỉ dùng VND => chèn USD phải bị từ chối")
    void wallet_usdCurrency_shouldBeRejectedWhenVndOnly() {
        String userId = seedUser("usd@test.com");

        assertCheckViolation(() -> jdbc.update(
                "INSERT INTO wallets (id, user_id, currency) VALUES (?, ?, 'USD')",
                UUID.randomUUID().toString(), userId),
                "chk_wallet_currency");
    }

    @Test
    @DisplayName("NOT NULL wallets.pending: chèn pending = NULL phải bị từ chối (mã 1048)")
    void wallet_nullPending_shouldBeRejected() {
        String userId = seedUser("nullpending@test.com");

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO wallets (id, user_id, pending) VALUES (?, ?, NULL)",
                UUID.randomUUID().toString(), userId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(ex -> {
                    SQLException sqlEx = findSqlException(ex);
                    assertThat((Object) sqlEx).isNotNull();
                    assertThat(sqlEx.getErrorCode()).isEqualTo(MYSQL_BAD_NULL_ERROR);
                });
    }

    @Test
    @DisplayName("CHECK wallets.pending: chèn pending âm phải bị từ chối (chk_wallet_pending)")
    void wallet_negativePending_shouldBeRejected() {
        String userId = seedUser("negpending@test.com");

        assertCheckViolation(() -> jdbc.update(
                "INSERT INTO wallets (id, user_id, pending) VALUES (?, ?, -1)",
                UUID.randomUUID().toString(), userId),
                "chk_wallet_pending");
    }

    @Test
    @DisplayName("CHECK transactions: amount = 0 bị từ chối (chk_txn_amount)")
    void transaction_zeroAmount_isRejected() {
        assertCheckViolation(() -> jdbc.update(
                "INSERT INTO transactions (reference_id, type, status, source_wallet_id, destination_wallet_id, amount) "
                        + "VALUES (?, 'TOP_UP', 'PENDING', ?, ?, 0)",
                UUID.randomUUID().toString(), walletA, walletA),
                "chk_txn_amount");
    }

    @Test
    @DisplayName("CHECK transactions: TOP_UP có nguồn khác đích bị từ chối (chk_txn_wallets_by_type)")
    void transaction_topUpDifferentWallets_isRejected() {
        assertCheckViolation(() -> jdbc.update(
                "INSERT INTO transactions (reference_id, type, status, source_wallet_id, destination_wallet_id, amount) "
                        + "VALUES (?, 'TOP_UP', 'PENDING', ?, ?, 100)",
                UUID.randomUUID().toString(), walletA, walletB),
                "chk_txn_wallets_by_type");
    }

    @Test
    @DisplayName("CHECK transactions: TRANSFER có nguồn bằng đích bị từ chối (chk_txn_wallets_by_type)")
    void transaction_transferSameWallet_isRejected() {
        assertCheckViolation(() -> jdbc.update(
                "INSERT INTO transactions (reference_id, type, status, source_wallet_id, destination_wallet_id, amount) "
                        + "VALUES (?, 'TRANSFER', 'PENDING', ?, ?, 100)",
                UUID.randomUUID().toString(), walletA, walletA),
                "chk_txn_wallets_by_type");
    }

    @Test
    @DisplayName("CHECK transactions: cả source_wallet_id và destination_wallet_id cùng NULL bị từ chối")
    void transaction_bothWalletsNull_isRejected() {
        assertCheckViolation(() -> jdbc.update(
                "INSERT INTO transactions (reference_id, type, status, source_wallet_id, destination_wallet_id, amount) "
                        + "VALUES (?, 'TRANSFER', 'PENDING', NULL, NULL, 100)",
                UUID.randomUUID().toString()),
                "chk_txn_has_wallet", "chk_txn_wallets_by_type");
    }

    @Test
    @DisplayName("Positive control wallets: chèn ví tối thiểu => nhận default 0 VND, ACTIVE, pending = 0")
    void wallet_validInsert_isAcceptedWithDefaults() {
        String userId = seedUser("valid@test.com");
        String walletId = UUID.randomUUID().toString();

        jdbc.update("INSERT INTO wallets (id, user_id) VALUES (?, ?)", walletId, userId);

        assertThat(
                jdbc.queryForObject("SELECT balance FROM wallets WHERE id = ?", java.math.BigDecimal.class, walletId))
                .isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("SELECT currency FROM wallets WHERE id = ?", String.class, walletId))
                .isEqualTo("VND");
        assertThat(jdbc.queryForObject("SELECT status FROM wallets WHERE id = ?", String.class, walletId))
                .isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("SELECT pending FROM wallets WHERE id = ?", Integer.class, walletId))
                .isZero();
    }

    @Test
    @DisplayName("Positive control transactions: TOP_UP / WITHDRAW (nguồn = đích) và TRANSFER (nguồn != đích) hợp lệ đều được chấp nhận")
    void transaction_validInserts_areAccepted() {
        String sql = "INSERT INTO transactions (reference_id, type, status, source_wallet_id, destination_wallet_id, amount) "
                + "VALUES (?, ?, 'PENDING', ?, ?, 100)";

        jdbc.update(sql, UUID.randomUUID().toString(), "TOP_UP", walletA, walletA);
        jdbc.update(sql, UUID.randomUUID().toString(), "WITHDRAW", walletA, walletA);
        jdbc.update(sql, UUID.randomUUID().toString(), "TRANSFER", walletA, walletB);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM transactions", Integer.class)).isEqualTo(3);
    }

    @Test
    @DisplayName("CHECK transactions: amount âm bị từ chối (chk_txn_amount)")
    void transaction_negativeAmount_isRejected() {
        assertCheckViolation(() -> jdbc.update(
                "INSERT INTO transactions (reference_id, type, status, source_wallet_id, destination_wallet_id, amount) "
                        + "VALUES (?, 'TOP_UP', 'PENDING', ?, ?, -5)",
                UUID.randomUUID().toString(), walletA, walletA),
                "chk_txn_amount");
    }

    @Test
    @DisplayName("CHECK transactions: WITHDRAW có nguồn khác đích bị từ chối (chk_txn_wallets_by_type)")
    void transaction_withdrawDifferentWallets_isRejected() {
        assertCheckViolation(() -> jdbc.update(
                "INSERT INTO transactions (reference_id, type, status, source_wallet_id, destination_wallet_id, amount) "
                        + "VALUES (?, 'WITHDRAW', 'PENDING', ?, ?, 100)",
                UUID.randomUUID().toString(), walletA, walletB),
                "chk_txn_wallets_by_type");
    }

    private Flyway flyway(String target) {
        var cfg = Flyway.configure()
                .cleanDisabled(false)
                .dataSource(dataSource)
                .locations("classpath:db/migration");
        if (target != null) {
            cfg.target(target);
        }
        return cfg.load();
    }

    private String seedLegacyWallet(String email, String balance, String pendingSql) {
        String userId = seedUser(email);
        String walletId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO wallets (id, user_id, balance, status, pending) VALUES (?, ?, ?, 'ACTIVE', "
                + pendingSql + ")", walletId, userId, new java.math.BigDecimal(balance));
        return walletId;
    }

    @Test
    @DisplayName("Migration V3 trên dữ liệu cũ: pending = NULL được backfill về 0, cột thành NOT NULL DEFAULT 0, dữ liệu khác giữ nguyên")
    void migrationV3_backfillsNullPending_andKeepsExistingData() {
        try {
            flyway(null).clean();
            flyway("2").migrate();

            String nullPendingWallet = seedLegacyWallet("legacy-null@test.com", "5000", "NULL");
            String normalWallet = seedLegacyWallet("legacy-normal@test.com", "7000", "3");
            assertThat(jdbc.queryForObject("SELECT pending FROM wallets WHERE id = ?", Integer.class,
                    nullPendingWallet))
                    .as("tiền đề: trước V3 cột pending phải cho phép NULL")
                    .isNull();

            flyway(null).migrate();

            assertThat(jdbc.queryForObject("SELECT pending FROM wallets WHERE id = ?", Integer.class,
                    nullPendingWallet)).isZero();
            assertThat(jdbc.queryForObject("SELECT pending FROM wallets WHERE id = ?", Integer.class,
                    normalWallet)).as("ví đã có pending hợp lệ không bị đụng tới").isEqualTo(3);
            assertThat(jdbc.queryForObject("SELECT balance FROM wallets WHERE id = ?",
                    java.math.BigDecimal.class, nullPendingWallet)).isEqualByComparingTo("5000");
            assertThat(jdbc.queryForObject("SELECT currency FROM wallets WHERE id = ?", String.class, normalWallet))
                    .isEqualTo("VND");

            assertThat(jdbc.queryForObject(
                    "SELECT is_nullable FROM information_schema.columns "
                            + "WHERE table_schema = DATABASE() AND table_name = 'wallets' AND column_name = 'pending'",
                    String.class)).isEqualTo("NO");
            assertThat(jdbc.queryForObject(
                    "SELECT column_default FROM information_schema.columns "
                            + "WHERE table_schema = DATABASE() AND table_name = 'wallets' AND column_name = 'pending'",
                    String.class)).isEqualTo("0");
        } finally {
            flyway(null).migrate();
        }
    }
}