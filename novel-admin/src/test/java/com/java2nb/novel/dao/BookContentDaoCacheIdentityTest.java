package com.java2nb.novel.dao;

import java.io.Reader;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class BookContentDaoCacheIdentityTest {

    @Test
    void resolvesBatchRowIdsToBookAndIndexIdsInOneMapperCall() throws Exception {
        PooledDataSource source = new PooledDataSource("org.h2.Driver",
            "jdbc:h2:mem:admin_cache_identity_" + UUID.randomUUID() + ";MODE=MySQL", "sa", "");
        try {
            JdbcTemplate jdbc = new JdbcTemplate(source);
            jdbc.execute("CREATE TABLE book_index(id BIGINT PRIMARY KEY, book_id BIGINT NOT NULL)");
            jdbc.execute("CREATE TABLE book_content(id BIGINT PRIMARY KEY, index_id BIGINT NOT NULL, content CLOB)");
            jdbc.update("INSERT INTO book_index(id,book_id) VALUES(201,101),(202,101)");
            jdbc.update("INSERT INTO book_content(id,index_id,content) VALUES(301,201,'A'),(302,202,'B')");

            Configuration configuration = new Configuration(
                new Environment("test", new JdbcTransactionFactory(), source));
            configuration.setMapUnderscoreToCamelCase(true);
            try (Reader mapperXml = Resources.getResourceAsReader("mybatis/novel/BookContentMapper.xml")) {
                new XMLMapperBuilder(mapperXml, configuration,
                    "mybatis/novel/BookContentMapper.xml", configuration.getSqlFragments()).parse();
            }

            try (var session = new SqlSessionFactoryBuilder().build(configuration).openSession()) {
                var identities = session.getMapper(BookContentDao.class)
                    .listCacheIdentitiesByIds(List.of(301L, 302L));

                assertThat(identities)
                    .extracting("bookId", "bookIndexId")
                    .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(101L, 201L),
                        org.assertj.core.groups.Tuple.tuple(101L, 202L));
            }
        } finally {
            source.forceCloseAll();
        }
    }
}
