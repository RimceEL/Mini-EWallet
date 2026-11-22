package com.backend.backend.transaction.concurrency;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.PessimisticLockingFailureException;
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
import com.backend.backend.transaction.customexception.DuplicateTransactionException;
import com.backend.backend.transaction.customexception.ExceedPendingException;
import com.backend.backend.transaction.customexception.TransactionError;
import com.backend.backend.transaction.customexception.TransactionRecordError;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringJUnitConfig(TransactionConcurrencyTest.Config.class)
@Timeout(value = 180, unit = TimeUnit.SECONDS)
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
            cfg.setConnectionTimeout(15_000);
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

    private String createWallet(BigDecimal balance, int pending) {
        String email = "u-" + UUID.randomUUID() + "@test.com";
        jdbc.update("INSERT INTO users (email, password) VALUES (?, ?)", email, "hash");
        String userId = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", String.class, email);
        String walletId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO wallets (id, user_id, balance, status, pending) VALUES (?, ?, ?, 'ACTIVE', ?)",
                walletId, userId, balance, pending);
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

    private Transaction seedPending(TransactionType type, String src, String dst, BigDecimal amount, int ageMinutes) {
        String ref = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO transactions (reference_id, type, status, source_wallet_id, "
                + "destination_wallet_id, amount, description, created_at) "
                + "VALUES (?, ?, 'PENDING', ?, ?, ?, 'stress', NOW() - INTERVAL ? MINUTE)",
                ref, type.name(), src, dst, amount, ageMinutes);
        jdbc.update("UPDATE wallets SET pending = pending + 1 WHERE id = ?", src);
        return Transaction.builder()
                .referenceId(ref)
                .type(type)
                .status(TransactionStatus.PENDING)
                .sourceWalletId(src)
                .destinationWalletId(dst)
                .amount(amount)
                .description("stress")
                .build();
    }

    private int countSuccessTransfers() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM transactions WHERE type = 'TRANSFER' AND status = 'SUCCESS'", Integer.class);
    }

    protected int countBySourceAndStatus(String walletId, String status) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM transactions WHERE source_wallet_id = ? AND status = ?",
                Integer.class, walletId, status);
    }

    private int countPending(String walletId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM transactions WHERE source_wallet_id = ? AND status = 'PENDING'",
                Integer.class, walletId);
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

    private static <T> List<Outcome<T>> runConcurrently(int poolSize, List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(poolSize);
        CountDownLatch ready = new CountDownLatch(poolSize);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Outcome<T>>> futures = new ArrayList<>();
            for (int i = 0; i < tasks.size(); i++) {
                Callable<T> task = tasks.get(i);
                boolean participateInBarrier = i < poolSize;
                futures.add(pool.submit(() -> {
                    if (participateInBarrier) {
                        ready.countDown();
                    }
                    go.await();
                    try {
                        return new Outcome<>(task.call(), null);
                    } catch (Throwable t) {
                        return new Outcome<>(null, t);
                    }
                }));
            }
            assertThat(ready.await(15, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            List<Outcome<T>> results = new ArrayList<>();
            for (Future<Outcome<T>> f : futures) {
                results.add(f.get(90, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
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

    @Test
    @DisplayName("Bất biến bảo toàn tiền: 8 ví, 240 thao tác hỗn hợp nạp/rút/chuyển/hết hạn đồng thời (seed cố định)")
    void conservationInvariant_mixedOperationsAcrossEightWallets_preservesTotalMoneyAndPending() throws Exception {
        Random rnd = new Random(20261122L);
        int walletCount = 8;
        BigDecimal initialPerWallet = BigDecimal.valueOf(5_000);
        List<String> wallets = new ArrayList<>();
        for (int i = 0; i < walletCount; i++) {
            wallets.add(createWallet(initialPerWallet, 0));
        }

        List<Transaction> seeded = new ArrayList<>();
        List<Callable<Object>> tasks = new ArrayList<>();

        for (int i = 0; i < 220; i++) {
            int srcIdx = rnd.nextInt(walletCount);
            String src = wallets.get(srcIdx);
            int roll = rnd.nextInt(10);
            int ageMinutes = rnd.nextInt(4) == 0 ? 31 : 0;

            Transaction tx;
            if (roll < 3) {
                BigDecimal amount = BigDecimal.valueOf((rnd.nextInt(10) + 1) * 100L);
                tx = seedPending(TransactionType.TOP_UP, src, src, amount, ageMinutes);
            } else if (roll < 6) {
                BigDecimal amount = BigDecimal.valueOf((rnd.nextInt(25) + 1) * 300L);
                tx = seedPending(TransactionType.WITHDRAW, src, src, amount, ageMinutes);
            } else {
                int dstIdx = (srcIdx + 1 + rnd.nextInt(walletCount - 1)) % walletCount;
                String dst = wallets.get(dstIdx);
                BigDecimal amount = BigDecimal.valueOf((rnd.nextInt(20) + 1) * 250L);
                tx = seedPending(TransactionType.TRANSFER, src, dst, amount, ageMinutes);
            }
            seeded.add(tx);
            tasks.add(() -> {
                transactionService.execute(tx);
                return null;
            });
        }

        for (int i = 0; i < 20; i++) {
            tasks.add(() -> transactionRepository.expirePending(30));
        }

        var results = runConcurrently(24, tasks);

        for (var outcome : results) {
            if (!outcome.ok()) {
                assertThat(outcome.error())
                        .isInstanceOfAny(TransactionError.class, PessimisticLockingFailureException.class);
            }
        }

        // Chống pass rỗng: nếu mọi execute đều lỗi thì các bất biến bên dưới vẫn đúng
        // một cách tầm thường
        Integer successRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM transactions WHERE status = 'SUCCESS'", Integer.class);
        Integer successTransfers = jdbc.queryForObject(
                "SELECT COUNT(*) FROM transactions WHERE type = 'TRANSFER' AND status = 'SUCCESS'", Integer.class);
        assertThat(successRows).as("phải có giao dịch thành công, nếu không test này vô nghĩa").isPositive();
        assertThat(successTransfers).as("phải có ít nhất một TRANSFER thành công").isPositive();

        BigDecimal totalTopUpSuccess = jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount), 0) FROM transactions WHERE type = 'TOP_UP' AND status = 'SUCCESS'",
                BigDecimal.class);
        BigDecimal totalWithdrawSuccess = jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount), 0) FROM transactions WHERE type = 'WITHDRAW' AND status = 'SUCCESS'",
                BigDecimal.class);
        BigDecimal totalBalanceAfter = jdbc.queryForObject(
                "SELECT COALESCE(SUM(balance), 0) FROM wallets", BigDecimal.class);
        BigDecimal totalBalanceBefore = initialPerWallet.multiply(BigDecimal.valueOf(walletCount));

        assertThat(totalBalanceAfter)
                .isEqualByComparingTo(totalBalanceBefore.add(totalTopUpSuccess).subtract(totalWithdrawSuccess));

        for (String w : wallets) {
            assertThat(balanceOf(w)).isGreaterThanOrEqualTo(BigDecimal.ZERO);
            assertThat(pendingOf(w)).isEqualTo(countPending(w));
        }

        for (String w : wallets) {
            BigDecimal incoming = jdbc.queryForObject(
                    "SELECT COALESCE(SUM(amount), 0) FROM transactions "
                            + "WHERE status = 'SUCCESS' AND ("
                            + "(type = 'TOP_UP' AND source_wallet_id = ?) OR "
                            + "(type = 'TRANSFER' AND destination_wallet_id = ?))",
                    BigDecimal.class, w, w);
            BigDecimal outgoing = jdbc.queryForObject(
                    "SELECT COALESCE(SUM(amount), 0) FROM transactions "
                            + "WHERE status = 'SUCCESS' AND ("
                            + "(type = 'WITHDRAW' AND source_wallet_id = ?) OR "
                            + "(type = 'TRANSFER' AND source_wallet_id = ?))",
                    BigDecimal.class, w, w);
            assertThat(balanceOf(w))
                    .as("Số dư ví %s phải khớp lịch sử giao dịch SUCCESS", w)
                    .isEqualByComparingTo(initialPerWallet.add(incoming).subtract(outgoing));
        }
    }

    @RepeatedTest(20)
    @DisplayName("Race: execute(TRANSFER) chạy đua với expirePending => chỉ 1 bên thắng, tiền không bị tách đôi, pending khớp")
    void executeTransfer_vs_expirePending_exactlyOneWins() throws Exception {
        String walletA = createWallet(BigDecimal.valueOf(5_000), 0);
        String walletB = createWallet(BigDecimal.valueOf(1_000), 0);
        Transaction tx = seedPending(TransactionType.TRANSFER, walletA, walletB, BigDecimal.valueOf(2_000), 31);

        var results = runConcurrently(2, List.of(
                () -> {
                    transactionService.execute(tx);
                    return null;
                },
                () -> transactionRepository.expirePending(30)));

        String finalStatus = statusOf(tx.getReferenceId());
        assertThat(finalStatus).isIn("SUCCESS", "FAILED");
        assertThat(results.get(1).ok()).isTrue();

        if ("SUCCESS".equals(finalStatus)) {
            assertThat(results.get(0).ok()).isTrue();
            assertThat(balanceOf(walletA)).isEqualByComparingTo("3000");
            assertThat(balanceOf(walletB)).isEqualByComparingTo("3000");
        } else {
            assertThat(results.get(0).error()).isInstanceOf(TransactionError.class);
            assertThat(balanceOf(walletA)).isEqualByComparingTo("5000");
            assertThat(balanceOf(walletB)).isEqualByComparingTo("1000");
        }
        assertThat(balanceOf(walletA).add(balanceOf(walletB))).isEqualByComparingTo("6000");
        assertThat(pendingOf(walletA)).isZero();
    }

    @RepeatedTest(15)
    @DisplayName("Race: execute(TRANSFER) chạy đua với khoá ví đích (LOCKED) => hoặc xong trọn vẹn, hoặc rollback sạch")
    void executeTransfer_vs_lockDestinationWallet_allOrNothing() throws Exception {
        String walletA = createWallet(BigDecimal.valueOf(5_000), 0);
        String walletB = createWallet(BigDecimal.valueOf(1_000), 0);
        Transaction tx = seedPending(TransactionType.TRANSFER, walletA, walletB, BigDecimal.valueOf(2_000), 0);

        var results = runConcurrently(2, List.of(
                () -> {
                    transactionService.execute(tx);
                    return null;
                },
                () -> jdbc.update("UPDATE wallets SET status = 'LOCKED' WHERE id = ?", walletB)));

        assertThat(results.get(1).ok()).isTrue();
        assertThat(balanceOf(walletA).add(balanceOf(walletB))).isEqualByComparingTo("6000");

        if (results.get(0).ok()) {
            assertThat(statusOf(tx.getReferenceId())).isEqualTo("SUCCESS");
            assertThat(balanceOf(walletA)).isEqualByComparingTo("3000");
            assertThat(balanceOf(walletB)).isEqualByComparingTo("3000");
            assertThat(pendingOf(walletA)).isZero();
        } else {
            assertThat(results.get(0).error()).isInstanceOf(TransactionError.class);
            assertThat(statusOf(tx.getReferenceId())).isEqualTo("PENDING");
            assertThat(balanceOf(walletA)).isEqualByComparingTo("5000");
            assertThat(balanceOf(walletB)).isEqualByComparingTo("1000");
            assertThat(pendingOf(walletA)).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("Race: chuyển tiền hai chiều A<->B đồng thời có giao dịch vượt số dư xen kẽ => không sinh/mất tiền")
    void bidirectionalTransfers_withInsufficientBalanceMix_conservesMoney() throws Exception {
        String walletA = createWallet(BigDecimal.valueOf(3_000), 0);
        String walletB = createWallet(BigDecimal.valueOf(3_000), 0);
        List<Callable<Object>> tasks = new ArrayList<>();

        for (int i = 0; i < 10; i++) {
            Transaction aToB = seedPending(TransactionType.TRANSFER, walletA, walletB, BigDecimal.valueOf(1_000), 0);
            Transaction bToA = seedPending(TransactionType.TRANSFER, walletB, walletA, BigDecimal.valueOf(1_000), 0);
            tasks.add(() -> {
                transactionService.execute(aToB);
                return null;
            });
            tasks.add(() -> {
                transactionService.execute(bToA);
                return null;
            });
        }

        var results = runConcurrently(20, tasks);

        // Test này tuyên bố "không deadlock": lỗi khoá (deadlock / lock timeout) KHÔNG
        // được coi là bình thường.
        assertThat(count(results, PessimisticLockingFailureException.class))
                .as("không được có deadlock / lock timeout khi chuyển tiền hai chiều")
                .isZero();

        AtomicReference<Throwable> unexpected = new AtomicReference<>();
        results.forEach(o -> {
            if (!o.ok() && !(o.error() instanceof TransactionError)) {
                unexpected.set(o.error());
            }
        });
        assertThat(unexpected.get()).isNull();
        assertThat(successes(results)).as("phải có giao dịch thành công").isPositive();
        assertThat(countSuccessTransfers()).isEqualTo((int) successes(results));
        assertThat(balanceOf(walletA)).isGreaterThanOrEqualTo(BigDecimal.ZERO);
        assertThat(balanceOf(walletB)).isGreaterThanOrEqualTo(BigDecimal.ZERO);
        assertThat(balanceOf(walletA).add(balanceOf(walletB))).isEqualByComparingTo("6000");
        assertThat(pendingOf(walletA)).isEqualTo(countPending(walletA));
        assertThat(pendingOf(walletB)).isEqualTo(countPending(walletB));
    }
}