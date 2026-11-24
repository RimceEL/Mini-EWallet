package com.backend.backend.transaction;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

public abstract class MySqlIntegrationTestBase {

    protected static final MySQLContainer MYSQL = new MySQLContainer(DockerImageName.parse("mysql:8.1"))
            .withDatabaseName("wallet_test")
            .withUsername("test")
            .withPassword("test");

    static {
        MYSQL.start();
    }

    public static HikariDataSource newDataSource(int maxPoolSize, int minIdle, long connectionTimeoutMs) {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(MYSQL.getJdbcUrl());
        cfg.setUsername(MYSQL.getUsername());
        cfg.setPassword(MYSQL.getPassword());
        cfg.setMaximumPoolSize(maxPoolSize);
        cfg.setMinimumIdle(minIdle);
        cfg.setConnectionTimeout(connectionTimeoutMs);
        HikariDataSource ds = new HikariDataSource(cfg);

        Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .load()
                .migrate();
        return ds;
    }

    @Autowired
    protected JdbcTemplate jdbc;

    @BeforeEach
    protected void cleanDatabase() {
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
}
