package com.java2nb.novel.dao;

import com.java2nb.novel.domain.*;
import java.io.Reader;
import java.util.UUID;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;

/** Real production MyBatis XML against H2, not a MySQL integration claim. */
class ChapterIdentityUpdateSqlTest {
    PooledDataSource source;
    JdbcTemplate jdbc;
    SqlSessionFactory factory;
    @BeforeEach void setup() throws Exception {
        source=new PooledDataSource("org.h2.Driver","jdbc:h2:mem:immutable_"+UUID.randomUUID()+";MODE=MySQL","sa","");
        jdbc=new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE book_index(id BIGINT PRIMARY KEY,book_id BIGINT,index_num INT,index_name VARCHAR(100),word_count INT,is_vip INT,book_price INT,storage_type VARCHAR(10),create_time TIMESTAMP,update_time TIMESTAMP)");
        jdbc.execute("CREATE TABLE book_content(id BIGINT PRIMARY KEY,index_id BIGINT,content CLOB)");
        jdbc.update("INSERT INTO book_index(id,book_id,storage_type,index_name) VALUES(201,101,'db','old')");
        jdbc.update("INSERT INTO book_content VALUES(301,201,'old')");
        var config=new Configuration(new Environment("test",new JdbcTransactionFactory(),source));
        config.setMapUnderscoreToCamelCase(true);
        for(String name:new String[]{"BookIndexMapper.xml","BookContentMapper.xml"}) {
            String path="mybatis/novel/"+name;
            try(Reader xml=Resources.getResourceAsReader(path)){new XMLMapperBuilder(xml,config,path,config.getSqlFragments()).parse();}
        }
        factory=new SqlSessionFactoryBuilder().build(config);
    }
    @AfterEach void close(){if(source!=null)source.forceCloseAll();}
    @Test void directoryUpdateNeverWritesIdentityEvenWhenDaoIsCalledDirectly() {
        var patch=new BookIndexDO();patch.setId(201L);patch.setBookId(999L);patch.setStorageType("txt");patch.setIndexName("new");
        try(var session=factory.openSession(true)){assertThat(session.getMapper(BookIndexDao.class).update(patch)).isEqualTo(1);}
        assertThat(jdbc.queryForObject("SELECT book_id FROM book_index WHERE id=201",Long.class)).isEqualTo(101L);
        assertThat(jdbc.queryForObject("SELECT storage_type FROM book_index WHERE id=201",String.class)).isEqualTo("db");
        assertThat(jdbc.queryForObject("SELECT index_name FROM book_index WHERE id=201",String.class)).isEqualTo("new");
    }
    @Test void bodyUpdateNeverWritesDirectoryEvenWhenDaoIsCalledDirectly() {
        var patch=new BookContentDO();patch.setId(301L);patch.setIndexId(999L);patch.setContent("new");
        try(var session=factory.openSession(true)){assertThat(session.getMapper(BookContentDao.class).update(patch)).isEqualTo(1);}
        assertThat(jdbc.queryForObject("SELECT index_id FROM book_content WHERE id=301",Long.class)).isEqualTo(201L);
        assertThat(jdbc.queryForObject("SELECT content FROM book_content WHERE id=301",String.class)).isEqualTo("new");
    }
}

