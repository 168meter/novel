package com.java2nb.novel.service.impl;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Opt-in: only a dedicated local schema; never accepts an arbitrary/production JDBC URL. */
@EnabledIfSystemProperty(named = "novel.purchase.mysql.it", matches = "true")
class UserPurchaseMySqlIT extends UserPurchaseTransactionTest {
    @Override HikariDataSource dataSource() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:mysql://127.0.0.1:3307/novel_plus_purchase_it"
            + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai");
        config.setUsername(System.getProperty("novel.mysql.username", "root"));
        config.setPassword(System.getProperty("novel.mysql.password", "123456"));
        config.setMaximumPoolSize(4);
        config.setConnectionTimeout(3000);
        return new HikariDataSource(config);
    }
}
