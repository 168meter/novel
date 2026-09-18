package com.java2nb.novel.mapper;

import com.java2nb.novel.entity.User;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import io.github.xxyopen.web.valid.AddGroup;
import io.github.xxyopen.web.valid.UpdateGroup;
import jakarta.validation.Validation;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class FrontUserAuthMapperTest {
    private PooledDataSource source;
    private SqlSessionFactory factory;
    private final LocalDateTime now = LocalDateTime.of(2026, 9, 18, 12, 0);

    @BeforeEach
    void setUp() throws Exception {
        source = new PooledDataSource("org.h2.Driver", "jdbc:h2:mem:auth_" + UUID.randomUUID()
            + ";MODE=MySQL;NON_KEYWORDS=USER;DB_CLOSE_DELAY=-1", "sa", "");
        source.setDefaultAutoCommit(true);
        sql("CREATE TABLE user(id BIGINT PRIMARY KEY, username VARCHAR(50), password VARCHAR(255),"
            + "email VARCHAR(254) UNIQUE,password_algorithm VARCHAR(20) DEFAULT 'ARGON2ID' NOT NULL,"
            + "token_version BIGINT DEFAULT 0 NOT NULL,email_verified_at TIMESTAMP,nick_name VARCHAR(50),"
            + "user_photo VARCHAR(100),user_sex TINYINT,account_balance BIGINT,status TINYINT,"
            + "create_time TIMESTAMP,update_time TIMESTAMP)",
            "INSERT INTO user(id,username,password,password_algorithm,token_version,status,nick_name)"
                + " VALUES(1,'13800138000','legacy-hash','MD5',7,0,'private profile')",
            "INSERT INTO user(id,email,password,password_algorithm,token_version,status,email_verified_at)"
                + " VALUES(2,'reader@example.com','argon-hash','ARGON2ID',0,0,'2026-09-18 12:00:00')");
        Configuration config = new Configuration(new Environment("test", new JdbcTransactionFactory(), source));
        try (var stream = Resources.getResourceAsStream("mybatis/mapping/UserMapper.xml")) {
            new XMLMapperBuilder(stream, config, "mybatis/mapping/UserMapper.xml", config.getSqlFragments()).parse();
        }
        factory = new SqlSessionFactoryBuilder().build(config);
    }

    @AfterEach
    void close() { if (source != null) source.forceCloseAll(); }

    @Test
    void passwordPreservesWhitespaceAndUsernameHasNoPhoneValidation() throws Exception {
        User user = new User();
        user.setPassword(" password with spaces ");
        assertThat(user.getPassword()).isEqualTo(" password with spaces ");
        var field = User.class.getDeclaredField("username");
        assertThat(field.getAnnotation(jakarta.validation.constraints.Pattern.class)).isNull();
        assertThat(field.getAnnotation(jakarta.validation.constraints.NotBlank.class)).isNull();
    }

    @Test
    void rejectsAuthenticationFieldsFromProfileAndLegacyRegistrationRequests() throws Exception {
        User user = new User();
        user.setPassword("test");
        user.setEmail("injected@example.com");
        user.setPasswordAlgorithm("MD5");
        user.setTokenVersion(99L);
        user.setEmailVerifiedAt(now);
        try (var validatorFactory = Validation.buildDefaultValidatorFactory()) {
            for (Class<?> group : new Class<?>[]{AddGroup.class, UpdateGroup.class}) {
                assertThat(validatorFactory.getValidator().validate(user, group))
                    .extracting(violation -> violation.getPropertyPath().toString())
                    .contains("email", "passwordAlgorithm", "tokenVersion", "emailVerifiedAt");
            }
        }
    }

    @Test
    void looksUpEmailAndLegacyPhoneWithOnlyAuthenticationFields() throws Exception {
        User email = auth("selectAuthByEmail", "reader@example.com").orElseThrow();
        assertThat(email.getId()).isEqualTo(2);
        assertThat(email.getEmail()).isEqualTo("reader@example.com");
        assertThat(email.getPasswordAlgorithm()).isEqualTo("ARGON2ID");
        assertThat(email.getEmailVerifiedAt()).isEqualTo(now);
        assertThat(email.getUsername()).isNull();
        User legacy = auth("selectAuthByLegacyUsername", "13800138000").orElseThrow();
        assertThat(legacy.getPassword()).isEqualTo("legacy-hash");
        assertThat(legacy.getTokenVersion()).isEqualTo(7L);
        assertThat(legacy.getNickName()).isNull();
        assertThat(auth("selectAuthByEmail", "missing@example.com")).isEmpty();
        assertThat(auth("selectAuthByLegacyUsername", "missing")).isEmpty();
    }

    @Test
    void tokenVersionIsNumericAndMissingUserIsEmpty() throws Exception {

        try (var session = factory.openSession()) {
            var mapper = session.getMapper(FrontUserMapper.class);
            assertThat(mapper.selectTokenVersion(1L)).isEqualTo(OptionalLong.of(7));
            assertThat(mapper.selectTokenVersion(999L)).isEqualTo(OptionalLong.empty());
        }
    }

    @Test
    void conditionalUpgradeRejectsWrongIdHashAndAlgorithm() throws Exception {
        assertThat(upgrade(999, "legacy-hash", "new")).isZero();
        assertThat(upgrade(1, "wrong-hash", "new")).isZero();
        assertThat(upgrade(2, "argon-hash", "new")).isZero();
        assertThat(auth("selectAuthByLegacyUsername", "13800138000").orElseThrow().getPassword())
            .isEqualTo("legacy-hash");
    }

    @Test
    void competingLegacyUpgradesSucceedExactlyOnce() throws Exception {

        var start = new CountDownLatch(1);
        var ready = new CountDownLatch(2);
        var connections = new java.util.concurrent.ConcurrentLinkedQueue<Connection>();
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> {
                try (var session = factory.openSession(true)) {
                    connections.add(session.getConnection()); ready.countDown();
                    start.await();
                    return session.getMapper(FrontUserMapper.class).upgradeLegacyPassword(1, "legacy-hash", "argon-one", now);
                }
            });
            var second = executor.submit(() -> {
                try (var session = factory.openSession(true)) {
                    connections.add(session.getConnection()); ready.countDown();
                    start.await();
                    return session.getMapper(FrontUserMapper.class).upgradeLegacyPassword(1, "legacy-hash", "argon-two", now);
                }
            });
            assertThat(ready.await(2, java.util.concurrent.TimeUnit.SECONDS))
                .as("both independent connections must be held before releasing updates").isTrue();
            assertThat(connections).hasSize(2);
            var held = connections.toArray(Connection[]::new);
            assertThat(held[0]).isNotSameAs(held[1]);
            assertThat(held[0].isClosed()).isFalse();
            assertThat(held[1].isClosed()).isFalse();
            start.countDown();
            assertThat(first.get(10, java.util.concurrent.TimeUnit.SECONDS) + second.get(10, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(1);
            User upgraded = auth("selectAuthByLegacyUsername", "13800138000").orElseThrow();
            assertThat(upgraded.getPassword()).isIn("argon-one", "argon-two");
            assertThat(upgraded.getPasswordAlgorithm()).isEqualTo("ARGON2ID");
            assertThat(upgraded.getTokenVersion()).isEqualTo(7L);
            try (var connection = source.getConnection(); var statement = connection.createStatement();
                 var rows = statement.executeQuery("SELECT update_time FROM user WHERE id=1")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getTimestamp(1).toLocalDateTime()).isEqualTo(now);
            }
        } finally { executor.shutdownNow(); }
    }

    @Test
    void generatedMapperRoundTripsAllNewColumns() throws Exception {
        User user = new User(); user.setId(3L); user.setPassword(" hash ");
        user.setEmail("new@example.com");
        user.setPasswordAlgorithm("ARGON2ID");
        user.setTokenVersion(4L);
        user.setEmailVerifiedAt(now);
        try (var session = factory.openSession(true)) {
            var mapper = session.getMapper(FrontUserMapper.class);
            assertThat(mapper.insert(user)).isEqualTo(1);
            User loaded = mapper.selectByPrimaryKey(3L).orElseThrow();
            assertThat(loaded.getEmail()).isEqualTo("new@example.com");
            assertThat(loaded.getPasswordAlgorithm()).isEqualTo("ARGON2ID");
            assertThat(loaded.getTokenVersion()).isEqualTo(4L);
            assertThat(loaded.getEmailVerifiedAt()).isEqualTo(now);
            loaded.setTokenVersion(5L);
            assertThat(mapper.updateByPrimaryKey(loaded)).isEqualTo(1);
            assertThat(mapper.selectByPrimaryKey(3L).orElseThrow().getTokenVersion()).isEqualTo(5L);
        }
    }

    private Optional<User> auth(String name, String value) {
        try (var session = factory.openSession()) {
            var mapper = session.getMapper(FrontUserMapper.class);
            return switch (name) {
                case "selectAuthByEmail" -> mapper.selectAuthByEmail(value);
                case "selectAuthByLegacyUsername" -> mapper.selectAuthByLegacyUsername(value);
                default -> throw new AssertionError("Unknown authentication query: " + name);
            };
        }
    }
    private int upgrade(long id, String oldHash, String newHash) {
        try (var session = factory.openSession(true)) {
            return session.getMapper(FrontUserMapper.class).upgradeLegacyPassword(id, oldHash, newHash, now);
        }
    }
    private void sql(String... statements) throws Exception {
        try (Connection connection = source.getConnection(); Statement statement = connection.createStatement()) {
            for (String sql : statements) statement.execute(sql);
        }
    }
}
