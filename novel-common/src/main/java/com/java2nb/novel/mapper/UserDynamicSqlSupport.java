package com.java2nb.novel.mapper;

import java.sql.JDBCType;
import java.util.Date;
import java.time.LocalDateTime;
import javax.annotation.Generated;
import org.mybatis.dynamic.sql.SqlColumn;
import org.mybatis.dynamic.sql.SqlTable;

public final class UserDynamicSqlSupport {
    @Generated("org.mybatis.generator.api.MyBatisGenerator")
    public static final User user = new User();

    @Generated("org.mybatis.generator.api.MyBatisGenerator")
    public static final SqlColumn<Long> id = user.id;

    @Generated("org.mybatis.generator.api.MyBatisGenerator")
    public static final SqlColumn<String> username = user.username;

    @Generated("org.mybatis.generator.api.MyBatisGenerator")
    public static final SqlColumn<String> password = user.password;

    public static final SqlColumn<String> email = user.email;
    public static final SqlColumn<String> passwordAlgorithm = user.passwordAlgorithm;
    public static final SqlColumn<Long> tokenVersion = user.tokenVersion;
    public static final SqlColumn<LocalDateTime> emailVerifiedAt = user.emailVerifiedAt;

    @Generated("org.mybatis.generator.api.MyBatisGenerator")
    public static final SqlColumn<String> nickName = user.nickName;

    @Generated("org.mybatis.generator.api.MyBatisGenerator")
    public static final SqlColumn<String> userPhoto = user.userPhoto;

    @Generated("org.mybatis.generator.api.MyBatisGenerator")
    public static final SqlColumn<Byte> userSex = user.userSex;

    @Generated("org.mybatis.generator.api.MyBatisGenerator")
    public static final SqlColumn<Long> accountBalance = user.accountBalance;

    @Generated("org.mybatis.generator.api.MyBatisGenerator")
    public static final SqlColumn<Byte> status = user.status;

    @Generated("org.mybatis.generator.api.MyBatisGenerator")
    public static final SqlColumn<Date> createTime = user.createTime;

    @Generated("org.mybatis.generator.api.MyBatisGenerator")
    public static final SqlColumn<Date> updateTime = user.updateTime;

    @Generated("org.mybatis.generator.api.MyBatisGenerator")
    public static final class User extends SqlTable {
        public final SqlColumn<Long> id = column("id", JDBCType.BIGINT);

        public final SqlColumn<String> username = column("username", JDBCType.VARCHAR);

        public final SqlColumn<String> password = column("password", JDBCType.VARCHAR);

        public final SqlColumn<String> email = column("email", JDBCType.VARCHAR);
        public final SqlColumn<String> passwordAlgorithm = column("password_algorithm", JDBCType.VARCHAR);
        public final SqlColumn<Long> tokenVersion = column("token_version", JDBCType.BIGINT);
        public final SqlColumn<LocalDateTime> emailVerifiedAt = column("email_verified_at", JDBCType.TIMESTAMP);

        public final SqlColumn<String> nickName = column("nick_name", JDBCType.VARCHAR);

        public final SqlColumn<String> userPhoto = column("user_photo", JDBCType.VARCHAR);

        public final SqlColumn<Byte> userSex = column("user_sex", JDBCType.TINYINT);

        public final SqlColumn<Long> accountBalance = column("account_balance", JDBCType.BIGINT);

        public final SqlColumn<Byte> status = column("status", JDBCType.TINYINT);

        public final SqlColumn<Date> createTime = column("create_time", JDBCType.TIMESTAMP);

        public final SqlColumn<Date> updateTime = column("update_time", JDBCType.TIMESTAMP);

        public User() {
            super("user");
        }
    }
}
