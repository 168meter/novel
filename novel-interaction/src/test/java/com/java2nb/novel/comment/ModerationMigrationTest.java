package com.java2nb.novel.comment;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.sql.*;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="CORRECTNESS_MYSQL_PREFIX",matches=".+")
class ModerationMigrationTest {
 Connection c;String schema;
 @BeforeEach void setup() throws Exception {
  String prefix=System.getenv("CORRECTNESS_MYSQL_PREFIX");if(!prefix.matches("cv_it_[a-z0-9_]{1,25}")) throw new IllegalArgumentException("Unsafe schema prefix");
  schema=prefix+"_mig_"+Long.toUnsignedString(System.nanoTime(),36);
  c=DriverManager.getConnection("jdbc:mysql://127.0.0.1:23307/?allowPublicKeyRetrieval=true&useSSL=false&serverTimezone=Asia/Shanghai&characterEncoding=UTF-8",System.getenv("CORRECTNESS_MYSQL_USER"),System.getenv("CORRECTNESS_MYSQL_PASSWORD"));
  exec("CREATE DATABASE `"+schema+"` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");exec("USE `"+schema+"`");
  exec("CREATE TABLE book(id BIGINT PRIMARY KEY,comment_count INT) ENGINE=InnoDB");
  exec("CREATE TABLE user(id BIGINT PRIMARY KEY) ENGINE=InnoDB");exec("INSERT INTO user VALUES(7),(8)");
  exec("CREATE TABLE book_comment(id BIGINT PRIMARY KEY,book_id BIGINT,create_user_id BIGINT,comment_content VARCHAR(512),location VARCHAR(50),create_time DATETIME,audit_status TINYINT,reply_count INT,UNIQUE(book_id,create_user_id)) ENGINE=InnoDB");
  exec("CREATE TABLE book_comment_reply(id BIGINT PRIMARY KEY,comment_id BIGINT,create_user_id BIGINT,reply_content VARCHAR(512),location VARCHAR(50),create_time DATETIME,audit_status TINYINT) ENGINE=InnoDB");
  exec("INSERT INTO book VALUES(1,99)");exec("INSERT INTO book_comment(id,book_id,create_user_id,comment_content,audit_status,reply_count) VALUES(10,1,7,'approved',1,9),(11,1,8,'pending',0,NULL)");
  exec("INSERT INTO book_comment_reply(id,comment_id,create_user_id,reply_content,audit_status) VALUES(20,10,7,'approved reply',1),(21,10,8,'rejected reply',2),(22,11,7,'approved under pending parent',1)");
  exec("SET @moderation_expected_database='"+schema+"'");exec("SET @moderation_writes_paused=1");
 }
 @AfterEach void evidence(TestInfo info) throws Exception {
  if(c==null)return;
  try {Path dir=Path.of(System.getenv("CORRECTNESS_EVIDENCE"));Files.createDirectories(dir);Files.writeString(dir.resolve("migration-"+schema+".txt"),"test="+info.getDisplayName()+"\nschema="+schema+"\nbookCount="+value("SELECT comment_count FROM book WHERE id=1"));}finally{c.close();}
 }
 void exec(String sql) throws Exception {try(var st=c.createStatement()){st.execute(sql);}}
 long value(String sql) throws Exception {try(var st=c.createStatement();var rs=st.executeQuery(sql)){rs.next();return rs.getLong(1);}}
 void script(String name) throws Exception {
  String delimiter=";";var pending=new StringBuilder();
  for(String line:Files.readAllLines(Path.of("../doc/sql/moderation-20261019",name))) {
   String trimmed=line.trim();if(trimmed.startsWith("--") || trimmed.isEmpty())continue;
   if(trimmed.startsWith("DELIMITER ")){delimiter=trimmed.substring(10).trim();continue;}
   pending.append(line).append('\n');if(trimmed.endsWith(delimiter)){String sql=pending.toString().trim();exec(sql.substring(0,sql.length()-delimiter.length()));pending.setLength(0);}
  }
  if(!pending.toString().isBlank())throw new IllegalStateException("Unterminated SQL");
 }
 @Test void recountAndImmediateRollbackPreserveStatesAndOldCounts() throws Exception {
  script("01-preflight.sql");script("02-add-version.sql");script("03-recount.sql");
  assertThat(value("SELECT comment_count FROM book WHERE id=1")).isEqualTo(1);
  assertThat(value("SELECT reply_count FROM book_comment WHERE id=10")).isEqualTo(1);
  assertThat(value("SELECT reply_count FROM book_comment WHERE id=11")).isEqualTo(1);
  assertThat(value("SELECT audit_status FROM book_comment WHERE id=11")).isZero();
  script("04-rollback-counts.sql");assertThat(value("SELECT comment_count FROM book WHERE id=1")).isEqualTo(99);
  assertThat(value("SELECT reply_count FROM book_comment WHERE id=10")).isEqualTo(9);
  assertThat(value("SELECT reply_count IS NULL FROM book_comment WHERE id=11")).isEqualTo(1);
 }
 @Test void rollbackRejectsNewContentAndDoesNotRestoreStaleCounts() throws Exception {
  script("02-add-version.sql");script("03-recount.sql");exec("UPDATE book_comment SET comment_content='changed',moderation_version=1 WHERE id=10");
  assertThatThrownBy(()->script("04-rollback-counts.sql")).isInstanceOf(SQLException.class);
  assertThat(value("SELECT comment_count FROM book WHERE id=1")).isEqualTo(1);
 }
 @Test void invalidLegacyStateBlocksDdlBeforeAnyVersionColumn() throws Exception {
  exec("UPDATE book_comment SET audit_status=9 WHERE id=10");
  assertThatThrownBy(()->script("02-add-version.sql")).isInstanceOf(SQLException.class);
  assertThat(value("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND column_name='moderation_version'")).isZero();
 }
}
