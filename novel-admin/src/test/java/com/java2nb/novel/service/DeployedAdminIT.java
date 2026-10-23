package com.java2nb.novel.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.java2nb.common.redis.shiro.SerializeUtils;
import com.java2nb.common.utils.MD5Utils;
import org.apache.shiro.session.Session;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import redis.clients.jedis.Jedis;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Only dedicated validation schema/Redis. Captcha read is test-only, not a public endpoint. */
@EnabledIfEnvironmentVariable(named="CORRECTNESS_DEPLOYED",matches="true")
class DeployedAdminIT {
    final ObjectMapper json=new ObjectMapper();
    final List<String> evidence=new ArrayList<>();
    final String url="http://127.0.0.1:28180";
    HttpClient client(){return HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).connectTimeout(java.time.Duration.ofSeconds(10)).build();}
    HttpResponse<String> request(HttpClient client,String path,String body) throws Exception {
        var b=HttpRequest.newBuilder(URI.create(url+path)).timeout(java.time.Duration.ofSeconds(30)).header("X-Requested-With","XMLHttpRequest");
        if(body==null)b.GET();else b.header("Content-Type","application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body));
        var r=client.send(b.build(),HttpResponse.BodyHandlers.ofString());
        // Login requests/cookies/captcha/passwords are deliberately never logged.
        if(!path.equals("/login") && !path.equals("/getVerify")) evidence.add(path+" status="+r.statusCode()+" body="+r.body());
        return r;
    }
    int code(HttpResponse<String> r) throws Exception {assertThat(r.statusCode()).isEqualTo(200);return json.readTree(r.body()).path("code").asInt(-1);}
    HttpClient login(String username,String password) throws Exception {
        var c=client();var r=request(c,"/getVerify",null);assertThat(r.statusCode()).isEqualTo(200);
        var cookies=((CookieManager)c.cookieHandler().orElseThrow()).getCookieStore().getCookies();
        String sid=cookies.stream().filter(k->k.getName().equals("JSESSIONID")).findFirst().orElseThrow().getValue();
        String captcha;
        try(var redis=new Jedis("127.0.0.1",26381)){
            redis.auth(System.getenv("CORRECTNESS_REDIS_PASSWORD"));
            var session=(Session)SerializeUtils.deserialize(redis.get(("shiro_redis_session:"+sid).getBytes(StandardCharsets.UTF_8)));
            assertThat(session).isNotNull();captcha=String.valueOf(session.getAttribute("RANDOMVALIDATECODEKEY"));
        }
        assertThat(code(request(c,"/login","username="+username+"&password="+password+"&verify="+captcha))).isZero();
        return c;
    }
    @Test void deployedShiroPermissionsAndCommentTransactions() throws Exception {
        var jdbc=new JdbcTemplate(new DriverManagerDataSource(System.getenv("CORRECTNESS_APP_JDBC"),System.getenv("CORRECTNESS_MYSQL_USER"),System.getenv("CORRECTNESS_MYSQL_PASSWORD")));
        assertThat(jdbc.queryForObject("SELECT label FROM correctness_identity WHERE id=1",String.class)).isEqualTo(System.getenv("CORRECTNESS_RUN_ID"));
        String pwd=System.getenv("CORRECTNESS_ADMIN_PASSWORD");
        assertThat(pwd).isNotBlank();
        for(int n=1;n<=2;n++)jdbc.update("INSERT INTO sys_user(user_id,username,name,password,dept_id,status) VALUES(?,?,?, ?,0,1)",990000000L+n,"cvuser"+n,"Synthetic verifier",MD5Utils.encrypt("cvuser"+n,pwd));
        jdbc.update("INSERT INTO sys_role(role_id,role_name) VALUES(990000001,'verification only')");
        jdbc.update("INSERT INTO sys_menu(menu_id,parent_id,name,perms,type,order_num) VALUES(990000001,0,'verification','novel:bookComment:bookComment,novel:bookComment:add,novel:bookComment:edit,novel:bookComment:remove,novel:bookComment:batchRemove',1,0)");
        jdbc.update("INSERT INTO sys_user_role(user_id,role_id) VALUES(990000001,990000001)");
        jdbc.update("INSERT INTO sys_role_menu(role_id,menu_id) VALUES(990000001,990000001)");
        Path file=Path.of(System.getenv("CORRECTNESS_EVIDENCE"),"deployed-admin-http.txt");
        try{
            var anon=request(client(),"/novel/bookComment/remove","id=123");assertThat(anon.statusCode()).isIn(302,401,403);
            var denied=login("cvuser2",pwd);
            assertThat(code(request(denied,"/novel/bookComment/remove","id=123"))).isEqualTo(403);
            var admin=login("cvuser1",pwd);
            assertThat(request(admin,"/index",null).statusCode()).isEqualTo(200);
            assertThat(code(request(admin,"/novel/bookComment/save","bookId=900099&createUserId=700000101&commentContent=admin-smoke&replyCount=999"))).isZero();
            long id=jdbc.queryForObject("SELECT id FROM book_comment WHERE book_id=900099 AND create_user_id=700000101",Long.class);
            assertThat(jdbc.queryForObject("SELECT reply_count FROM book_comment WHERE id=?",Integer.class,id)).isZero();
            assertThat(code(request(denied,"/novel/bookComment/remove","id="+id))).isEqualTo(403);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM book_comment WHERE id=?",Long.class,id)).isEqualTo(1);
            assertThat(code(request(admin,"/novel/bookComment/update","id="+id+"&bookId=999999&createUserId=999999&replyCount=999&commentContent=admin-edited"))).isZero();
            assertThat(jdbc.queryForObject("SELECT book_id FROM book_comment WHERE id=?",Long.class,id)).isEqualTo(900099);
            assertThat(jdbc.queryForObject("SELECT create_user_id FROM book_comment WHERE id=?",Long.class,id)).isEqualTo(700000101);
            assertThat(jdbc.queryForObject("SELECT reply_count FROM book_comment WHERE id=?",Integer.class,id)).isZero();
            long before=jdbc.queryForObject("SELECT comment_count FROM book WHERE id=900099",Long.class);
            assertThat(code(request(admin,"/novel/bookComment/remove","id="+id))).isZero();
            assertThat(code(request(admin,"/novel/bookComment/remove","id="+id))).isNotZero();
            assertThat(jdbc.queryForObject("SELECT comment_count FROM book WHERE id=900099",Long.class)).isEqualTo(before-1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM book_comment WHERE id=?",Long.class,id)).isZero();
            for(int n=102;n<=103;n++)assertThat(code(request(admin,"/novel/bookComment/save","bookId=900099&createUserId=700000"+n+"&commentContent=batch-smoke"))).isZero();
            var ids=jdbc.queryForList("SELECT id FROM book_comment WHERE book_id=900099 AND create_user_id IN (700000102,700000103) ORDER BY id",Long.class);
            assertThat(ids).hasSize(2);
            jdbc.update("INSERT INTO book_comment_reply(comment_id,create_user_id,reply_content) VALUES(?,700000100,'batch reply')",ids.get(0));
            jdbc.update("UPDATE book_comment SET reply_count=1 WHERE id=?",ids.get(0));
            assertThat(code(request(admin,"/novel/bookComment/batchRemove","ids%5B%5D="+ids.get(0)+"&ids%5B%5D="+ids.get(1)+"&ids%5B%5D="+ids.get(0)))).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM book_comment_reply WHERE comment_id=?",Long.class,ids.get(0))).isZero();
            assertThat(jdbc.queryForObject("SELECT comment_count FROM book WHERE id=900099",Long.class)).isEqualTo(jdbc.queryForObject("SELECT COUNT(*) FROM book_comment WHERE book_id=900099",Long.class));
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM book_comment c WHERE c.book_id=900099 AND c.reply_count<>(SELECT COUNT(*) FROM book_comment_reply r WHERE r.comment_id=c.id)",Long.class)).isZero();
        }finally{Files.write(file,evidence);}
    }
}
