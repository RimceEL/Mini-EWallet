package com.backend.backend.global;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
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
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

@Testcontainers
@SpringJUnitConfig(SchemaConstraintTest.Config.class)
@Timeout(value = 120, unit = TimeUnit.SECONDS)
public class SchemaConstraintTest {

    private static final int MYSQL_CHECK_CONSTRAINT_VIOLATED = 3819;
    private static final int MYSQL_BAD_NULL_ERROR = 1048;

    @Container
    static MySQLContainer mysql = new MySQLContainer(DockerImageName.parse("mysql:8.1"))
            .withDatabaseName("wallet_test")
            .withUsername("test")
            .withPassword("test");

    @TestConfiguration(proxyBeanMethods = false)
    static class Config {

        @Bean(destroyMethod = "close")
        HikariDataSource dataSource() {
            HikariConfig cfg = new HikariConfig();
            cfg.setJdbcUrl(mysql.getJdbcUrl());
            cfg.setUsername(mysql.getUsername());
            cfg.setPassword(mysql.getPassword());
            HikariDataSource ds = new HikariDataSource(cfg);

            Flyway.configure()
                    .dataSource(ds)
                    .locations("classpath:db/migration")
                    .load()
                    .migrate();
            return ds;
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }
    }

    @Autowired
    private JdbcTemplate jdbc;

    private String walletA;
    private String walletB;

    @BeforeEach
    void setUp() {
        jdbc.execute((ConnectionCallback<Void>) con -> {
            try (Statement st = con.createStatement()) {
                st.execute("SET FOREIGN_KEY_CHECKS = 0");
                List<String> tables = new ArrayList<>();
                try (ResultSet rs = st.executeQuery(
                        "SELECT table_name FROM information_schema.tables "
                                + "WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE' "
                                + "AND table_name <> 'flyway_schema_history'")) {
                    while (rs.next()) {
                        tables.add(rs.getString(1));
                    }
                }
                for (String table : tables) {
                    st.execute("TRUNCATE TABLE `" + table + "`");
                }
                st.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
            return null;
        });

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
    @DisplayName("[GIẢ THUYẾT V3] CHECK wallets: hệ thống chỉ dùng VND => chèn USD phải bị từ chối")
    void wallet_usdCurrency_shouldBeRejectedWhenVndOnly() {
        String userId = seedUser("usd@test.com");

        assertCheckViolation(() -> jdbc.update(
                "INSERT INTO wallets (id, user_id, currency) VALUES (?, ?, 'USD')",
                UUID.randomUUID().toString(), userId),
                "chk_wallet_currency");
    }

    @Test
    @DisplayName("[GIẢ THUYẾT V3] NOT NULL wallets.pending: chèn pending = NULL phải bị từ chối (mã 1048)")
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
    @DisplayName("[GIẢ THUYẾT V3] CHECK wallets.pending: chèn pending âm phải bị từ chối (chk_wallet_pending)")
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
}
