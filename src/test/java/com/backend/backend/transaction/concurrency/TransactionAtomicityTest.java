package com.backend.backend.transaction.concurrency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.backend.backend.transaction.Transaction;
import com.backend.backend.transaction.TransactionRepository;
import com.backend.backend.transaction.TransactionService;
import com.backend.backend.transaction.TransactionStatus;
import com.backend.backend.transaction.TransactionType;
import com.backend.backend.transaction.customexception.TransactionError;
import com.backend.backend.transaction.customexception.TransactionRecordError;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

@Testcontainers
@SpringJUnitConfig(TransactionAtomicityTest.Config.class)
@Timeout(value = 120, unit = TimeUnit.SECONDS)
public class TransactionAtomicityTest {

    @Container
    static MySQLContainer mysql = new MySQLContainer(DockerImageName.parse("mysql:8.1"))
            .withDatabaseName("wallet_test")
            .withUsername("test")
            .withPassword("test");

    @TestConfiguration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @Import({ TransactionRepository.class, TransactionService.class })
    static class Config {

        @Bean(destroyMethod = "close")
        HikariDataSource dataSource() {
            HikariConfig cfg = new HikariConfig();
            cfg.setJdbcUrl(mysql.getJdbcUrl());
            cfg.setUsername(mysql.getUsername());
            cfg.setPassword(mysql.getPassword());
            cfg.setMaximumPoolSize(10);
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

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }
    }

    @Autowired
    private TransactionService transactionService;

    @MockitoSpyBean
    private TransactionRepository transactionRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void resetState() {
        reset(transactionRepository);
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
    }

    private void createWalletWithId(String walletId, String balance, int pending) {
        String email = walletId + "-" + UUID.randomUUID() + "@test.com";
        jdbc.update("INSERT INTO users (email, password) VALUES (?, ?)", email, "hash");
        String userId = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", String.class, email);
        jdbc.update("INSERT INTO wallets (id, user_id, balance, status, pending) VALUES (?, ?, ?, 'ACTIVE', ?)",
                walletId, userId, new BigDecimal(balance), pending);
    }

    private Transaction seedPendingTransfer(String sourceId, String destId, String amount) {
        String ref = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO transactions (reference_id, type, status, source_wallet_id, "
                + "destination_wallet_id, amount, description) VALUES (?, 'TRANSFER', 'PENDING', ?, ?, ?, 'test')",
                ref, sourceId, destId, new BigDecimal(amount));
        return Transaction.builder()
                .referenceId(ref)
                .type(TransactionType.TRANSFER)
                .status(TransactionStatus.PENDING)
                .sourceWalletId(sourceId)
                .destinationWalletId(destId)
                .amount(new BigDecimal(amount))
                .description("test")
                .build();
    }

    private BigDecimal balanceOf(String id) {
        return jdbc.queryForObject("SELECT balance FROM wallets WHERE id = ?", BigDecimal.class, id);
    }

    private int pendingOf(String id) {
        return jdbc.queryForObject("SELECT pending FROM wallets WHERE id = ?", Integer.class, id);
    }

    private String statusOf(String ref) {
        return jdbc.queryForObject("SELECT status FROM transactions WHERE reference_id = ?", String.class, ref);
    }

    @ParameterizedTest(name = "nguồn={0}, đích={1}")
    @CsvSource({ "wallet-a, wallet-b", "wallet-b, wallet-a" })
    @DisplayName("TRANSFER: nguồn hết tiền lúc execute => rollback sạch (kể cả chiều credit chạy trước debit)")
    void execute_transferSourceBecameInsufficient_rollsBackEverything(String sourceId, String destId) {
        createWalletWithId(sourceId, "1000", 1);
        createWalletWithId(destId, "0", 0);
        Transaction tx = seedPendingTransfer(sourceId, destId, "800");

        jdbc.update("UPDATE wallets SET balance = 100 WHERE id = ?", sourceId);

        assertThatThrownBy(() -> transactionService.execute(tx))
                .isInstanceOf(TransactionError.class);

        assertThat(balanceOf(destId)).isEqualByComparingTo("0");
        assertThat(balanceOf(sourceId)).isEqualByComparingTo("100");
        assertThat(statusOf(tx.getReferenceId())).isEqualTo("PENDING");
        assertThat(pendingOf(sourceId)).isEqualTo(1);
    }

    @ParameterizedTest(name = "nguồn={0}, đích={1}")
    @CsvSource({ "wallet-a, wallet-b", "wallet-b, wallet-a" })
    @DisplayName("TRANSFER: ví đích bị LOCKED lúc execute => rollback sạch (kể cả chiều debit chạy trước credit)")
    void execute_transferDestinationBecameLocked_rollsBackEverything(String sourceId, String destId) {
        createWalletWithId(sourceId, "1000", 1);
        createWalletWithId(destId, "0", 0);
        Transaction tx = seedPendingTransfer(sourceId, destId, "800");

        jdbc.update("UPDATE wallets SET status = 'LOCKED' WHERE id = ?", destId);

        assertThatThrownBy(() -> transactionService.execute(tx))
                .isInstanceOf(TransactionError.class);

        assertThat(balanceOf(sourceId)).isEqualByComparingTo("1000");
        assertThat(balanceOf(destId)).isEqualByComparingTo("0");
        assertThat(statusOf(tx.getReferenceId())).isEqualTo("PENDING");
        assertThat(pendingOf(sourceId)).isEqualTo(1);
    }

    @Test
    @DisplayName("TRANSFER: lỗi DB ngay tại decreasePending (sau khi đã cộng/trừ tiền) => rollback toàn bộ số dư và status")
    void execute_whenDecreasePendingThrowsDbError_rollsBackMoneyAndStatus() {
        createWalletWithId("wallet-a", "1000", 1);
        createWalletWithId("wallet-b", "500", 0);
        Transaction tx = seedPendingTransfer("wallet-a", "wallet-b", "400");

        doThrow(new DataAccessResourceFailureException("simulated DB drop"))
                .when(transactionRepository).decreasePending(anyString());

        assertThatThrownBy(() -> transactionService.execute(tx))
                .isInstanceOf(DataAccessResourceFailureException.class);

        assertThat(balanceOf("wallet-a")).isEqualByComparingTo("1000");
        assertThat(balanceOf("wallet-b")).isEqualByComparingTo("500");
        assertThat(statusOf(tx.getReferenceId())).isEqualTo("PENDING");
        assertThat(pendingOf("wallet-a")).isEqualTo(1);
    }

    @Test
    @DisplayName("transactionRecord: INSERT lỗi DB sau khi đã increasePending => rollback bộ đếm pending về 0")
    void transactionRecord_whenInsertFails_rollsBackPendingIncrement() {
        createWalletWithId("wallet-a", "1000", 0);
        Transaction tx = Transaction.builder()
                .referenceId(UUID.randomUUID().toString())
                .type(TransactionType.TOP_UP)
                .status(TransactionStatus.PENDING)
                .sourceWalletId("wallet-a")
                .destinationWalletId("wallet-a")
                .amount(new BigDecimal("500"))
                .description("test")
                .build();

        doThrow(new DataAccessResourceFailureException("insert failed"))
                .when(transactionRepository).transactionRecord(tx);

        assertThatThrownBy(() -> transactionService.transactionRecord(tx))
                .isInstanceOf(TransactionRecordError.class);

        assertThat(pendingOf("wallet-a")).isZero();
    }
}
