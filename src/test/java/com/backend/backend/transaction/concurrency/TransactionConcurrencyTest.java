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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.zaxxer.hikari.HikariConfig;
import com.backend.backend.transaction.Transaction;
import com.backend.backend.transaction.TransactionRepository;
import com.backend.backend.transaction.TransactionService;
import com.backend.backend.transaction.TransactionStatus;
import com.backend.backend.transaction.TransactionType;
import com.backend.backend.transaction.customexception.DuplicateTransactionException;
import com.backend.backend.transaction.customexception.ExceedPendingException;
import com.backend.backend.transaction.customexception.TransactionError;
import com.backend.backend.transaction.customexception.TransactionRecordError;
import com.zaxxer.hikari.HikariDataSource;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringJUnitConfig(TransactionConcurrencyTest.Config.class)
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class TransactionConcurrencyTest {

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
        jdbc.update("INSERT INTO users (email, password) VALUES (?, ?)", email, "test-password-hash");
        Object userId = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Object.class, email);

        String walletId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO wallets (id, user_id, balance, status, pending) "
                + "VALUES (?, ?, ?, ?, 0)", walletId, userId, balance, status);
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

    protected Transaction seedPendingTransaction(TransactionType type, String sourceWalletId,
            String destinationWalletId, BigDecimal amount, int ageMinutes) {
        Transaction tx = newTransaction(type, sourceWalletId, destinationWalletId, amount);
        insertPendingTransaction(tx.getReferenceId(), type, sourceWalletId, destinationWalletId, amount,
                ageMinutes);
        return tx;
    }

    protected int countBySourceAndStatus(String walletId, String status) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM transactions WHERE source_wallet_id = ? AND status = ?",
                Integer.class, walletId, status);
    }

    protected BigDecimal balanceOf(String walletId) {
        return jdbc.queryForObject("SELECT balance FROM wallets WHERE id = ?", BigDecimal.class, walletId);
    }

    protected int pendingOf(String walletId) {
        return jdbc.queryForObject("SELECT pending FROM wallets WHERE id = ?", Integer.class, walletId);
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

    private static final BigDecimal THOUSAND = BigDecimal.valueOf(1000);

    @Test
    @DisplayName("Ghi giao dịch: 8 luồng cùng referenceId => đúng 1 bản ghi, 7 luồng Duplicate, pending = 1")
    void record_sameReferenceId_createsExactlyOne() throws Exception {
        String wallet = createWallet(BigDecimal.valueOf(1_000_000));
        Transaction tx = newTransaction(TransactionType.TOP_UP, wallet, wallet, THOUSAND);

        var results = runConcurrently(8, () -> {
            transactionService.transactionRecord(tx);
            return null;
        });

        assertThat(successes(results)).isEqualTo(1);
        assertThat(count(results, DuplicateTransactionException.class)).isEqualTo(7);
        assertThat(countTransactions(tx.getReferenceId())).isEqualTo(1);
        assertThat(pendingOf(wallet)).isEqualTo(1);
    }

    @Test
    @DisplayName("Ghi giao dịch: 20 giao dịch khác nhau cùng một ví => đúng 5 được ghi, pending không vượt 5")
    void record_manyDistinct_neverExceedsPendingLimit() throws Exception {
        String wallet = createWallet(BigDecimal.valueOf(1_000_000));

        var results = runConcurrently(20, () -> {
            transactionService.transactionRecord(newTransaction(TransactionType.TOP_UP, wallet, wallet, THOUSAND));
            return null;
        });

        assertThat(successes(results)).isEqualTo(5);
        assertThat(count(results, ExceedPendingException.class)).isEqualTo(15);
        assertThat(pendingOf(wallet)).isEqualTo(5);
        assertThat(countBySourceAndStatus(wallet, "PENDING")).isEqualTo(5);
    }

    @Test
    @DisplayName("Execute: 10 luồng cùng thực thi MỘT giao dịch rút => chỉ trừ tiền 1 lần, pending giảm 1 lần")
    void execute_sameWithdraw_debitsExactlyOnce() throws Exception {
        String wallet = createWallet(BigDecimal.valueOf(10_000));
        setPending(wallet, 3);
        Transaction tx = seedPendingTransaction(TransactionType.WITHDRAW, wallet, wallet, THOUSAND, 0);

        var results = runConcurrently(10, () -> {
            transactionService.execute(tx);
            return null;
        });

        assertThat(successes(results)).isEqualTo(1);
        assertThat(count(results, TransactionError.class)).isEqualTo(9);
        assertThat(balanceOf(wallet)).isEqualByComparingTo("9000");
        assertThat(statusOf(tx.getReferenceId())).isEqualTo("SUCCESS");
        assertThat(pendingOf(wallet)).isEqualTo(2);
    }

    @Test
    @DisplayName("Execute: 10 luồng cùng thực thi MỘT giao dịch chuyển => tiền chỉ di chuyển 1 lần")
    void execute_sameTransfer_movesMoneyExactlyOnce() throws Exception {
        String walletA = createWallet(BigDecimal.valueOf(10_000));
        String walletB = createWallet(BigDecimal.ZERO);
        setPending(walletA, 3);
        Transaction tx = seedPendingTransaction(TransactionType.TRANSFER, walletA, walletB, THOUSAND, 0);

        var results = runConcurrently(10, () -> {
            transactionService.execute(tx);
            return null;
        });

        assertThat(successes(results)).isEqualTo(1);
        assertThat(count(results, TransactionError.class)).isEqualTo(9);
        assertThat(balanceOf(walletA)).isEqualByComparingTo("9000");
        assertThat(balanceOf(walletB)).isEqualByComparingTo("1000");
        assertThat(totalBalance(walletA, walletB)).isEqualByComparingTo("10000");
        assertThat(pendingOf(walletA)).isEqualTo(2);
    }

    @Test
    @DisplayName("Execute: 20 giao dịch rút 1.000 từ ví có 10.000 => đúng 10 thành công, số dư về 0, không âm")
    void execute_manyWithdrawals_insufficientBalance_neverNegative() throws Exception {
        String wallet = createWallet(BigDecimal.valueOf(10_000));
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            Transaction tx = seedPendingTransaction(TransactionType.WITHDRAW, wallet, wallet, THOUSAND, 0);
            tasks.add(() -> {
                transactionService.execute(tx);
                return null;
            });
        }

        var results = runConcurrently(tasks);

        assertThat(successes(results)).isEqualTo(10);
        assertThat(count(results, TransactionError.class)).isEqualTo(10);
        assertThat(balanceOf(wallet)).isEqualByComparingTo("0");
        assertThat(countBySourceAndStatus(wallet, "SUCCESS")).isEqualTo(10);
        assertThat(countBySourceAndStatus(wallet, "PENDING")).isEqualTo(10);
    }

    @Test
    @DisplayName("Execute: 10 lần nạp 1.000 và 10 lần rút 500 đồng thời trên một ví => không mất cập nhật")
    void execute_mixedTopUpAndWithdraw_noLostUpdate() throws Exception {
        String wallet = createWallet(BigDecimal.valueOf(100_000));
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            Transaction topUp = seedPendingTransaction(TransactionType.TOP_UP, wallet, wallet, THOUSAND, 0);
            Transaction withdraw = seedPendingTransaction(TransactionType.WITHDRAW, wallet, wallet,
                    BigDecimal.valueOf(500), 0);
            tasks.add(() -> {
                transactionService.execute(topUp);
                return null;
            });
            tasks.add(() -> {
                transactionService.execute(withdraw);
                return null;
            });
        }

        var results = runConcurrently(tasks);

        assertThat(successes(results)).isEqualTo(20);
        assertThat(balanceOf(wallet)).isEqualByComparingTo("105000");
    }

    @Test
    @DisplayName("Execute: chuyển tiền hai chiều A<->B đồng thời => không deadlock, tổng tiền được bảo toàn")
    void execute_bidirectionalTransfers_noDeadlock_balancesConserved() throws Exception {
        String walletA = createWallet(BigDecimal.valueOf(10_000));
        String walletB = createWallet(BigDecimal.valueOf(10_000));
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            Transaction aToB = seedPendingTransaction(TransactionType.TRANSFER, walletA, walletB,
                    BigDecimal.valueOf(300), 0);
            Transaction bToA = seedPendingTransaction(TransactionType.TRANSFER, walletB, walletA,
                    BigDecimal.valueOf(100), 0);
            tasks.add(() -> {
                transactionService.execute(aToB);
                return null;
            });
            tasks.add(() -> {
                transactionService.execute(bToA);
                return null;
            });
        }

        var results = runConcurrently(tasks);

        assertThat(successes(results)).isEqualTo(20);
        assertThat(balanceOf(walletA)).isEqualByComparingTo("8000");
        assertThat(balanceOf(walletB)).isEqualByComparingTo("12000");
        assertThat(totalBalance(walletA, walletB)).isEqualByComparingTo("20000");
    }

    @RepeatedTest(20)
    @DisplayName("Execute chạy đua với expirePending trên cùng giao dịch => chỉ một bên thắng, pending giảm đúng 1 lần")
    void execute_vs_expirePending_exactlyOneWins() throws Exception {
        String wallet = createWallet(BigDecimal.valueOf(10_000));
        setPending(wallet, 3);
        Transaction tx = seedPendingTransaction(TransactionType.WITHDRAW, wallet, wallet, THOUSAND, 31);

        List<Callable<Object>> tasks = new ArrayList<>();
        tasks.add(() -> {
            transactionService.execute(tx);
            return null;
        });
        tasks.add(() -> transactionRepository.expirePending(30));

        var results = runConcurrently(tasks);

        String finalStatus = statusOf(tx.getReferenceId());
        assertThat(finalStatus).isIn("SUCCESS", "FAILED");
        assertThat(results.get(1).ok()).isTrue();

        if (finalStatus.equals("SUCCESS")) {
            assertThat(results.get(0).ok()).isTrue();
            assertThat(balanceOf(wallet)).isEqualByComparingTo("9000");
        } else {
            assertThat(results.get(0).error()).isInstanceOf(TransactionError.class);
            assertThat(balanceOf(wallet)).isEqualByComparingTo("10000");
        }
        assertThat(pendingOf(wallet)).isEqualTo(2);
    }

    @Test
    @DisplayName("expirePending: 6 luồng chạy đồng thời => mỗi giao dịch quá hạn được xử lý đúng 1 lần")
    void expirePending_concurrent_eachTransactionExpiredOnce() throws Exception {
        List<String> wallets = new ArrayList<>();
        for (int w = 0; w < 5; w++) {
            String wallet = createWallet(BigDecimal.valueOf(1_000));
            setPending(wallet, 5);
            for (int i = 0; i < 4; i++) {
                seedPendingTransaction(TransactionType.WITHDRAW, wallet, wallet, BigDecimal.valueOf(100), 31);
            }
            wallets.add(wallet);
        }

        var results = runConcurrently(6, () -> transactionRepository.expirePending(30));

        assertThat(successes(results)).isEqualTo(6);
        int totalExpired = 0;
        for (var outcome : results) {
            totalExpired += outcome.value();
        }
        assertThat(totalExpired).isEqualTo(20);
        for (String wallet : wallets) {
            assertThat(countBySourceAndStatus(wallet, "FAILED")).isEqualTo(4);
            assertThat(countBySourceAndStatus(wallet, "PENDING")).isEqualTo(0);
            assertThat(pendingOf(wallet)).isEqualTo(1);
        }
    }

    @RepeatedTest(15)
    @DisplayName("Ghi giao dịch mới chạy đua với expirePending => bộ đếm pending luôn khớp số giao dịch PENDING thật")
    void record_vs_expirePending_pendingCounterStaysConsistent() throws Exception {
        String wallet = createWallet(BigDecimal.valueOf(1_000_000));
        setPending(wallet, 5);
        for (int i = 0; i < 5; i++) {
            seedPendingTransaction(TransactionType.TOP_UP, wallet, wallet, THOUSAND, 31);
        }

        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            tasks.add(() -> {
                transactionService.transactionRecord(
                        newTransaction(TransactionType.TOP_UP, wallet, wallet, THOUSAND));
                return null;
            });
        }
        tasks.add(() -> transactionRepository.expirePending(30));

        var results = runConcurrently(tasks);

        for (int i = 0; i < 4; i++) {
            var outcome = results.get(i);
            if (!outcome.ok()) {
                assertThat(outcome.error()).isInstanceOfAny(ExceedPendingException.class,
                        TransactionRecordError.class);
            }
        }
        var expireOutcome = results.get(4);
        if (!expireOutcome.ok()) {
            assertThat(expireOutcome.error()).isInstanceOf(PessimisticLockingFailureException.class);
        }

        assertThat(pendingOf(wallet)).isEqualTo(countBySourceAndStatus(wallet, "PENDING"));
        assertThat(pendingOf(wallet)).isBetween(0, 5);
    }
}