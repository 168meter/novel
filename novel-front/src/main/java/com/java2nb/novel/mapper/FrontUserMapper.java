package com.java2nb.novel.mapper;

import org.apache.ibatis.annotations.Param;
import com.java2nb.novel.entity.User;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * @author Administrator
 */
public interface FrontUserMapper extends UserMapper {

    int upgradeLegacyPassword(@Param("userId") long userId, @Param("oldHash") String oldHash,
        @Param("newHash") String newHash, @Param("now") LocalDateTime now);

    Optional<User> selectAuthByEmail(@Param("normalizedEmail") String normalizedEmail);

    Optional<User> selectAuthByLegacyUsername(@Param("username") String username);

    // MyBatis supports Optional<T>, but does not unwrap OptionalLong as a scalar result.
    default OptionalLong selectTokenVersion(long userId) {
        Long version = selectTokenVersionValue(userId);
        return version == null ? OptionalLong.empty() : OptionalLong.of(version);
    }

    Long selectTokenVersionValue(@Param("userId") long userId);



    void addUserBalance(@Param("userId") Long userId, @Param("amount") Integer amount);


}
