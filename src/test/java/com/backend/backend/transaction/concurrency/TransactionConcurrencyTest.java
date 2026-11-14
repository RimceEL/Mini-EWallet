package com.backend.backend.transaction.concurrency;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
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
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringJUnitConfig(TransactionConcurrencyTest.Config.class)
@Timeout(value = 120, unit = TimeUnit.SECONDS)
public class TransactionConcurrencyTest {

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
            cfg.setMaximumPoolSize(40);
            cfg.setMinimumIdle(10);
            cfg.setConnectionTimeout(10_000);
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
    protected TransactionService transactionService;
    @Autowired
    protected TransactionRepository transactionRepository;
    @Autowired
    protected JdbcTemplate jdbc;

    @BeforeEach
    void cleanDatabase() {
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

    protected String createWallet(BigDecimal balance) {
        return createWallet(balance, "ACTIVE");
    }

    protected String createWallet(BigDecimal balance, String status) {
        String email = "user-" + UUID.randomUUID() + "@test.com";
        jdbc.update("INSERT INTO users (email) VALUES (?)", email);
        Object userId = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Object.class, email);

        String walletId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO wallets (id, user_id, balance, version, status, pending) "
                + "VALUES (?, ?, ?, 0, ?, 0)", walletId, userId, balance, status);
        return walletId;
    }

    protected void insertPendingTransaction(String referenceId, TransactionType type, String sourceWalletId,
            String destinationWalletId, BigDecimal amount, int ageMinutes) {
        jdbc.update("INSERT INTO transactions (reference_id, type, status, source_wallet_id, "
                + "destination_wallet_id, amount, description, created_at) "
                + "VALUES (?, ?, 'PENDING', ?, ?, ?, 'test', NOW() - INTERVAL ? MINUTE)",
                referenceId, type.name(), sourceWalletId, destinationWalletId, amount, ageMinutes);
    }

    protected void setPending(String walletId, int pending) {
        jdbc.update("UPDATE wallets SET pending = ? WHERE id = ?", pending, walletId);
    }

    protected static Transaction newTransaction(TransactionType type, String sourceWalletId,
            String destinationWalletId, BigDecimal amount) {
        return Transaction.builder()
                .referenceId(UUID.randomUUID().toString())
                .type(type)
                .status(TransactionStatus.PENDING)
                .sourceWalletId(sourceWalletId)
                .destinationWalletId(destinationWalletId)
                .amount(amount)
                .description("test")
                .build();
    }

    protected BigDecimal balanceOf(String walletId) {
        return jdbc.queryForObject("SELECT balance FROM wallets WHERE id = ?", BigDecimal.class, walletId);
    }

    protected int pendingOf(String walletId) {
        return jdbc.queryForObject("SELECT pending FROM wallets WHERE id = ?", Integer.class, walletId);
    }

    protected long versionOf(String walletId) {
        return jdbc.queryForObject("SELECT version FROM wallets WHERE id = ?", Long.class, walletId);
    }

    protected String statusOf(String referenceId) {
        return jdbc.queryForObject("SELECT status FROM transactions WHERE reference_id = ?", String.class,
                referenceId);
    }

    protected int countTransactions(String referenceId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM transactions WHERE reference_id = ?", Integer.class,
                referenceId);
    }

    protected BigDecimal totalBalance(String... walletIds) {
        BigDecimal total = BigDecimal.ZERO;
        for (String id : walletIds) {
            total = total.add(balanceOf(id));
        }
        return total;
    }

    protected record Outcome<T>(T value, Throwable error) {
        boolean ok() {
            return error == null;
        }
    }

    protected static <T> List<Outcome<T>> runConcurrently(List<Callable<T>> tasks) throws Exception {
        int n = tasks.size();
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Outcome<T>>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    try {
                        return new Outcome<T>(task.call(), null);
                    } catch (Throwable t) {
                        return new Outcome<T>(null, t);
                    }
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            List<Outcome<T>> results = new ArrayList<>();
            for (Future<Outcome<T>> f : futures) {
                results.add(f.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    protected static <T> List<Outcome<T>> runConcurrently(int threads, Callable<T> task) throws Exception {
        return runConcurrently(Collections.nCopies(threads, task));
    }

    protected static long successes(List<? extends Outcome<?>> results) {
        return results.stream().filter(o -> o.ok()).count();
    }

    protected static long count(List<? extends Outcome<?>> results, Class<? extends Throwable> type) {
        return results.stream().filter(o -> type.isInstance(o.error())).count();
    }
}