package com.java2nb.novel.service.impl;

import com.java2nb.novel.mapper.FrontBookIndexMapper;
import java.util.UUID;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class BookChapterOwnershipTest {
    @Test
    void actualChapterSqlBindsBothIdsAndPreservesFreeAndVipMetadata() throws Exception {
        var source = new PooledDataSource("org.h2.Driver",
            "jdbc:h2:mem:ownership_" + UUID.randomUUID() + ";MODE=MySQL", "sa", "");
        try {
            var jdbc = new JdbcTemplate(source);
            jdbc.execute("CREATE TABLE book_index(id BIGINT PRIMARY KEY,book_id BIGINT,index_num INT,"
                + "index_name VARCHAR(100),word_count INT,is_vip TINYINT,book_price INT,storage_type VARCHAR(10),"
                + "create_time TIMESTAMP,update_time TIMESTAMP)");
            jdbc.update("INSERT INTO book_index(id,book_id,index_num,is_vip,book_price,storage_type)"
                + " VALUES(101,10,1,0,0,'db'),(201,20,1,1,30,'db')");
            Configuration config = new Configuration(new Environment("test", new JdbcTransactionFactory(), source));
            config.addMapper(FrontBookIndexMapper.class);
            try (var session = new SqlSessionFactoryBuilder().build(config).openSession()) {
                var mapper = session.getMapper(FrontBookIndexMapper.class);
                var constructor = BookServiceImpl.class.getDeclaredConstructors()[0];
                Class<?>[] types = constructor.getParameterTypes();
                Object[] dependencies = new Object[types.length];
                for (int i = 0; i < types.length; i++) {
                    dependencies[i] = types[i] == FrontBookIndexMapper.class ? mapper : mock(types[i]);
                }
                var books = (BookServiceImpl) constructor.newInstance(dependencies);
                assertThat(books.findBookIndex(10L, 101L)).hasValueSatisfying(chapter -> {
                    assertThat(chapter.getBookId()).isEqualTo(10L);
                    assertThat(chapter.getIsVip()).isZero();
                    assertThat(chapter.getStorageType()).isEqualTo("db");
                });
                assertThat(books.findBookIndex(20L, 201L)).hasValueSatisfying(chapter -> {
                    assertThat(chapter.getBookId()).isEqualTo(20L);
                    assertThat(chapter.getIsVip()).isEqualTo((byte) 1);
                    assertThat(chapter.getBookPrice()).isEqualTo(30);
                });
                assertThat(books.findBookIndex(10L, 201L)).isEmpty();
                assertThat(books.findBookIndex(20L, 101L)).isEmpty();
                assertThat(books.findBookIndex(10L, 999L)).isEmpty();
            }
        } finally {
            source.forceCloseAll();
        }
    }
}
