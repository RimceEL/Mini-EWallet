package com.backend.backend.transaction.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
import org.junit.jupiter.params.provider.ValueSource;
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
import com.backend.backend.transaction.TransactionStatus;
import com.backend.backend.transaction.TransactionType;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

@Testcontainers
@SpringJUnitConfig(TransactionRepositoryTest.Config.class)
@Timeout(value = 120, unit = TimeUnit.SECONDS)
public class TransactionRepositoryTest {

    @Container
    static MySQLContainer mysql = new MySQLContainer(DockerImageName.parse("mysql:8.1"))
            .withDatabaseName("wallet_test")
            .withUsername("test")
            .withPassword("test");

    @TestConfiguration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @Import(TransactionRepository.class)
    static class Config {

        @Bean(destroyMethod = "close")
        HikariDataSource dataSource() {
            HikariConfig cfg = new HikariConfig();
            cfg.setJdbcUrl(mysql.getJdbcUrl());
            cfg.setUsername(mysql.getUsername());
            cfg.setPassword(mysql.getPassword());
            cfg.setMaximumPoolSize(10);
            cfg.setMinimumIdle(2);
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
    private TransactionRepository repo;

    @Autowired
    private JdbcTemplate jdbc;

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

    private String createWallet(String email, String balance, String status, Integer pending) {
        jdbc.update("INSERT INTO users (email, password) VALUES (?, ?)", email, "hash");
        String userId = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", String.class, email);
        String walletId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO wallets (id, user_id, balance, status, pending) VALUES (?, ?, ?, ?, ?)",
                walletId, userId, new BigDecimal(balance), status, pending);
        return walletId;
    }

    private String createWallet(String balance, String status, Integer pending) {
        return createWallet("u-" + UUID.randomUUID() + "@test.com", balance, status, pending);
    }

    private BigDecimal balanceOf(String walletId) {
        return jdbc.queryForObject("SELECT balance FROM wallets WHERE id = ?", BigDecimal.class, walletId);
    }

    private Integer pendingOf(String walletId) {
        return jdbc.queryForObject("SELECT pending FROM wallets WHERE id = ?", Integer.class, walletId);
    }

    private String statusOf(String referenceId) {
        return jdbc.queryForObject("SELECT status FROM transactions WHERE reference_id = ?", String.class, referenceId);
    }

    private String seedPending(String walletId, int ageMinutes) {
        String ref = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO transactions (reference_id, type, status, source_wallet_id, "
                + "destination_wallet_id, amount, created_at) "
                + "VALUES (?, 'TOP_UP', 'PENDING', ?, ?, 1000, NOW() - INTERVAL ? MINUTE)",
                ref, walletId, walletId, ageMinutes);
        return ref;
    }

    // ======================= credit / debit / transfer =======================

    @Test
    @DisplayName("credit: ví ACTIVE => cộng đúng số dư")
    void credit_whenActive_addsBalance() {
        String w = createWallet("1000", "ACTIVE", 0);

        assertThat(repo.credit(w, new BigDecimal("500"))).isTrue();
        assertThat(balanceOf(w)).isEqualByComparingTo("1500");
    }

    @ParameterizedTest(name = "status = {0}")
    @ValueSource(strings = { "LOCKED", "PENDING" })
    @DisplayName("credit: ví không ACTIVE => trả false, số dư giữ nguyên")
    void credit_whenWalletNotActive_returnsFalse(String status) {
        String w = createWallet("1000", status, 0);

        assertThat(repo.credit(w, new BigDecimal("500"))).isFalse();
        assertThat(balanceOf(w)).isEqualByComparingTo("1000");
    }

    @Test
    @DisplayName("debit: rút đúng bằng số dư => trả true, số dư về 0")
    void debit_amountEqualsBalance_succeeds() {
        String w = createWallet("1000", "ACTIVE", 0);

        assertThat(repo.debit(w, new BigDecimal("1000"))).isTrue();
        assertThat(balanceOf(w)).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("debit: vượt số dư 1 đơn vị => trả false, số dư giữ nguyên")
    void debit_amountExceedsBalance_returnsFalse() {
        String w = createWallet("1000", "ACTIVE", 0);

        assertThat(repo.debit(w, new BigDecimal("1001"))).isFalse();
        assertThat(balanceOf(w)).isEqualByComparingTo("1000");
    }

    @Test
    @DisplayName("debit: ví LOCKED dù đủ tiền => trả false, số dư giữ nguyên")
    void debit_whenLocked_returnsFalse() {
        String w = createWallet("5000", "LOCKED", 0);

        assertThat(repo.debit(w, new BigDecimal("1000"))).isFalse();
        assertThat(balanceOf(w)).isEqualByComparingTo("5000");
    }

    @ParameterizedTest(name = "nguồn={0}, đích={1}")
    @CsvSource({ "wallet-a, wallet-b", "wallet-b, wallet-a" })
    @DisplayName("transfer: chuyển thành công theo cả hai chiều so sánh ID")
    void transfer_bothIdOrders_movesMoneyCorrectly(String srcId, String dstId) {
        jdbc.update("INSERT INTO users (email, password) VALUES (?, ?)", srcId + "@test.com", "hash");
        String u1 = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", String.class, srcId + "@test.com");
        jdbc.update("INSERT INTO wallets (id, user_id, balance, status, pending) VALUES (?, ?, 5000, 'ACTIVE', 0)",
                srcId, u1);

        jdbc.update("INSERT INTO users (email, password) VALUES (?, ?)", dstId + "@test.com", "hash");
        String u2 = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", String.class, dstId + "@test.com");
        jdbc.update("INSERT INTO wallets (id, user_id, balance, status, pending) VALUES (?, ?, 1000, 'ACTIVE', 0)",
                dstId, u2);

        assertThat(repo.transfer(srcId, dstId, new BigDecimal("2000"))).isTrue();
        assertThat(balanceOf(srcId)).isEqualByComparingTo("3000");
        assertThat(balanceOf(dstId)).isEqualByComparingTo("3000");
    }

    // ======================= pending counters =======================

    @Test
    @DisplayName("increasePending: dừng ở 5, lần thứ 6 trả false")
    void increasePending_stopsAtFive() {
        String w = createWallet("0", "ACTIVE", 0);

        for (int i = 0; i < 5; i++) {
            assertThat(repo.increasePending(w)).isTrue();
        }
        assertThat(repo.increasePending(w)).isFalse();
        assertThat(pendingOf(w)).isEqualTo(5);
    }

    @Test
    @DisplayName("decreasePending: không giảm xuống dưới 0")
    void decreasePending_neverNegative() {
        String w = createWallet("0", "ACTIVE", 0);

        assertThat(repo.decreasePending(w)).isFalse();
        assertThat(pendingOf(w)).isZero();
    }

    @Test
    @DisplayName("decreasePendingBy: trừ lớn hơn giá trị hiện tại => kẹp về 0 bằng GREATEST")
    void decreasePendingBy_clampsAtZero() {
        String w = createWallet("0", "ACTIVE", 2);

        repo.decreasePendingBy(w, 5);

        assertThat(pendingOf(w)).isZero();
    }

    // ======================= ownership / precheck / record / status
    // =======================

    @Test
    @DisplayName("isUserWallet: chỉ trả true khi khớp cả walletId lẫn email chủ sở hữu")
    void isUserWallet_matchesOnlyOwner() {
        String wA = createWallet("a@test.com", "0", "ACTIVE", 0);
        createWallet("b@test.com", "0", "ACTIVE", 0);

        assertThat(repo.isUserWallet(wA, "a@test.com")).isTrue();
        assertThat(repo.isUserWallet(wA, "b@test.com")).isFalse();
        assertThat(repo.isUserWallet("unknown-wallet", "a@test.com")).isFalse();
    }

    @Test
    @DisplayName("canDebit & isActiveWallet: kiểm tra đúng biên số dư và trạng thái")
    void precheckQueries_respectBalanceAndStatus() {
        String active = createWallet("1000", "ACTIVE", 0);
        String locked = createWallet("1000", "LOCKED", 0);

        assertThat(repo.isActiveWallet(active)).isTrue();
        assertThat(repo.isActiveWallet(locked)).isFalse();
        assertThat(repo.canDebit(active, new BigDecimal("1000"))).isTrue();
        assertThat(repo.canDebit(active, new BigDecimal("1001"))).isFalse();
        assertThat(repo.canDebit(locked, new BigDecimal("100"))).isFalse();
    }

    @Test
    @DisplayName("transactionRecord & findTransactionByRefId: luôn ghi status PENDING bất kể đầu vào và đọc lại đủ trường")
    void transactionRecord_forcesPendingStatus_andRoundTrips() {
        String w = createWallet("1000", "ACTIVE", 0);
        String ref = UUID.randomUUID().toString();
        Transaction tx = Transaction.builder()
                .referenceId(ref)
                .type(TransactionType.TOP_UP)
                .status(TransactionStatus.SUCCESS) // cố tình truyền SUCCESS
                .sourceWalletId(w)
                .destinationWalletId(w)
                .amount(new BigDecimal("500"))
                .description("Nap tien")
                .build();

        assertThat(repo.transactionRecord(tx)).isTrue();

        Optional<Transaction> found = repo.findTransactionByRefId(ref);
        assertThat(found).isPresent();
        assertThat(found.get().getStatus()).isEqualTo(TransactionStatus.PENDING);
        assertThat(found.get().getAmount()).isEqualByComparingTo("500");
        assertThat(found.get().getDescription()).isEqualTo("Nap tien");
        assertThat(found.get().getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("updateStatus: chỉ chuyển trạng thái khi đang PENDING, gọi lại lần 2 trả false")
    void updateStatus_onlyTransitionsFromPending() {
        String w = createWallet("1000", "ACTIVE", 1);
        String ref = seedPending(w, 0);

        assertThat(repo.updateStatus(ref, TransactionStatus.SUCCESS)).isTrue();
        assertThat(statusOf(ref)).isEqualTo("SUCCESS");

        assertThat(repo.updateStatus(ref, TransactionStatus.FAILED)).isFalse();
        assertThat(statusOf(ref)).isEqualTo("SUCCESS");
    }

    // ======================= expirePending mốc thời gian =======================

    @Test
    @DisplayName("expirePending(30): giao dịch 29 phút giữ PENDING, giao dịch 31 phút sang FAILED và giảm pending đúng 1")
    void expirePending_respectsTimeThreshold() {
        String w = createWallet("0", "ACTIVE", 2);
        String fresh = seedPending(w, 29);
        String old = seedPending(w, 31);

        assertThat(repo.expirePending(30)).isEqualTo(1);

        assertThat(statusOf(old)).isEqualTo("FAILED");
        assertThat(statusOf(fresh)).isEqualTo("PENDING");
        assertThat(pendingOf(w)).isEqualTo(1);
    }

    @Test
    @DisplayName("expirePending(30): bỏ qua giao dịch đã SUCCESS dù quá 30 phút")
    void expirePending_ignoresAlreadySuccessTransactions() {
        String w = createWallet("0", "ACTIVE", 0);
        String oldSuccess = seedPending(w, 45);
        jdbc.update("UPDATE transactions SET status = 'SUCCESS' WHERE reference_id = ?", oldSuccess);

        assertThat(repo.expirePending(30)).isZero();
        assertThat(statusOf(oldSuccess)).isEqualTo("SUCCESS");
        assertThat(pendingOf(w)).isZero();
    }
}
