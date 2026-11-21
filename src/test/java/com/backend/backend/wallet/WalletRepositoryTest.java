package com.backend.backend.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
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

import com.backend.backend.wallet.customexception.UserNotFoundException;
import com.backend.backend.wallet.customexception.WalletOpenedException;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

@Testcontainers
@SpringJUnitConfig(WalletRepositoryTest.Config.class)
@Timeout(value = 120, unit = TimeUnit.SECONDS)
public class WalletRepositoryTest {

    @Container
    static MySQLContainer mysql = new MySQLContainer(DockerImageName.parse("mysql:8.1"))
            .withDatabaseName("wallet_test")
            .withUsername("test")
            .withPassword("test");

    @TestConfiguration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @Import({ WalletRepository.class, WalletService.class })
    static class Config {

        @Bean(destroyMethod = "close")
        HikariDataSource dataSource() {
            HikariConfig cfg = new HikariConfig();
            cfg.setJdbcUrl(mysql.getJdbcUrl());
            cfg.setUsername(mysql.getUsername());
            cfg.setPassword(mysql.getPassword());
            cfg.setMaximumPoolSize(20);
            cfg.setMinimumIdle(5);
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
    private WalletRepository walletRepository;

    @Autowired
    private WalletService walletService;

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

    private String seedUser(String email) {
        jdbc.update("INSERT INTO users (email, password) VALUES (?, ?)", email, "hashed-password");
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", String.class, email);
    }

    private int countWallets() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM wallets", Integer.class);
        return count == null ? 0 : count;
    }

    @Test
    @DisplayName("createWalletByUserEmail: user tồn tại => tạo đúng 1 ví với giá trị mặc định (0 VND, ACTIVE, pending=0)")
    void createWalletByUserEmail_whenUserExists_shouldInsertWalletWithDefaults() {
        String email = "an@gmail.com";
        String userId = seedUser(email);

        walletRepository.createWalletByUserEmail(email);

        Optional<Wallet> found = walletRepository.findWalletByUserEmail(email);
        assertThat(found).isPresent();
        Wallet wallet = found.get();
        assertThat(wallet.getId()).isNotBlank();
        assertThat(wallet.getUserId()).isEqualTo(userId);
        assertThat(wallet.getBalance()).isEqualByComparingTo("0.0000");
        assertThat(wallet.getCurrency()).isEqualTo("VND");
        assertThat(wallet.getStatus()).isEqualTo("ACTIVE");
        assertThat(wallet.getPending()).isEqualTo(0);
        assertThat(wallet.getCreatedAt()).isNotNull();
        assertThat(wallet.getUpdatedAt()).isNotNull();
        assertThat(countWallets()).isEqualTo(1);
    }

    @Test
    @DisplayName("createWalletByUserEmail: user đã có ví => ném DuplicateKeyException, không tạo thêm ví")
    void createWalletByUserEmail_whenWalletAlreadyExists_shouldThrowDuplicateKeyException() {
        String email = "an@gmail.com";
        seedUser(email);
        walletRepository.createWalletByUserEmail(email);

        assertThatThrownBy(() -> walletRepository.createWalletByUserEmail(email))
                .isInstanceOf(DuplicateKeyException.class);
        assertThat(countWallets()).isEqualTo(1);
    }

    @Test
    @DisplayName("[GIẢ THUYẾT] openWallet: email không tồn tại trong DB => phải ném UserNotFoundException, không âm thầm thành công")
    void openWallet_whenUserDoesNotExist_shouldThrowUserNotFoundException() {
        assertThatThrownBy(() -> walletService.openWallet("khong-ton-tai@gmail.com"))
                .isInstanceOf(UserNotFoundException.class);
        assertThat(countWallets()).isZero();
    }

    @Test
    @DisplayName("findWalletByUserEmail: user tồn tại nhưng chưa mở ví => trả về Optional.empty()")
    void findWalletByUserEmail_whenUserHasNoWallet_shouldReturnEmpty() {
        String email = "chua-mo-vi@gmail.com";
        seedUser(email);

        assertThat(walletRepository.findWalletByUserEmail(email)).isEmpty();
    }

    @Test
    @DisplayName("findWalletByUserEmail: email không tồn tại => trả về Optional.empty()")
    void findWalletByUserEmail_whenEmailDoesNotExist_shouldReturnEmpty() {
        assertThat(walletRepository.findWalletByUserEmail("missing@gmail.com")).isEmpty();
    }

    @Test
    @DisplayName("findWalletByUserEmail: nhiều user cùng có ví => chỉ trả đúng ví thuộc email được truy vấn")
    void findWalletByUserEmail_whenMultipleUsersExist_shouldReturnMatchingWalletOnly() {
        String userA = seedUser("a@gmail.com");
        String userB = seedUser("b@gmail.com");
        walletRepository.createWalletByUserEmail("a@gmail.com");
        walletRepository.createWalletByUserEmail("b@gmail.com");

        Optional<Wallet> walletA = walletRepository.findWalletByUserEmail("a@gmail.com");
        Optional<Wallet> walletB = walletRepository.findWalletByUserEmail("b@gmail.com");

        assertThat(walletA).isPresent();
        assertThat(walletB).isPresent();
        assertThat(walletA.get().getUserId()).isEqualTo(userA);
        assertThat(walletB.get().getUserId()).isEqualTo(userB);
        assertThat(walletA.get().getId()).isNotEqualTo(walletB.get().getId());
    }

    private record Outcome(Throwable error) {
        boolean ok() {
            return error == null;
        }
    }

    @Test
    @DisplayName("openWallet đồng thời: 10 luồng mở ví cho cùng 1 user => đúng 1 thành công, 9 ném WalletOpenedException, DB có đúng 1 ví")
    void openWallet_concurrentSameUser_exactlyOneSucceeds() throws Exception {
        String email = "concurrent@gmail.com";
        seedUser(email);

        int threads = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Outcome>> futures = new ArrayList<>();
            for (Callable<Void> task : Collections.<Callable<Void>>nCopies(threads, () -> {
                walletService.openWallet(email);
                return null;
            })) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    try {
                        task.call();
                        return new Outcome(null);
                    } catch (Throwable t) {
                        return new Outcome(t);
                    }
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();

            List<Outcome> results = new ArrayList<>();
            for (Future<Outcome> f : futures) {
                results.add(f.get(30, TimeUnit.SECONDS));
            }

            long okCount = results.stream().filter(Outcome::ok).count();
            long openedCount = results.stream().filter(o -> o.error() instanceof WalletOpenedException).count();
            assertThat(okCount).isEqualTo(1);
            assertThat(openedCount).isEqualTo(threads - 1);
            assertThat(countWallets()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }
}
