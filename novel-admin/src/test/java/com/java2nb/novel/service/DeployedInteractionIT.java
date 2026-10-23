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
@EnabledIfEnvironmentVariable(named="JOINT_DEPLOYED",matches="true")
class DeployedInteractionIT {
    final ObjectMapper json=new ObjectMapper();
    final List<String> evidence=new ArrayList<>();
    final String url=System.getenv("JOINT_ADMIN_URL");
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
        try(var redis=new Jedis("127.0.0.1",Integer.parseInt(System.getenv("JOINT_REDIS_PORT")))){
            redis.auth(System.getenv("CORRECTNESS_REDIS_PASSWORD"));
            var session=(Session)SerializeUtils.deserialize(redis.get(("shiro_redis_session:"+sid).getBytes(StandardCharsets.UTF_8)));
            assertThat(session).isNotNull();captcha=String.valueOf(session.getAttribute("RANDOMVALIDATECODEKEY"));
        }
        assertThat(code(request(c,"/login","username="+username+"&password="+password+"&verify="+captcha))).isZero();
        return c;
    }

    JdbcTemplate jdbc;
    final long book=990900001L, owner=990700001L;
    String token;
    String front=System.getenv("JOINT_FRONT_URL");
    long n(String sql,Object...args){return jdbc.queryForObject(sql,Long.class,args);}
    long version(String table,long id){return n("SELECT moderation_version FROM "+table+" WHERE id=?",id);}
    com.fasterxml.jackson.databind.JsonNode front(String path,String body,int expected)throws Exception {
        var b=HttpRequest.newBuilder(URI.create(front+path)).timeout(java.time.Duration.ofSeconds(25));
        if(token!=null)b.header("Authorization",token);
        if(body==null)b.GET();else b.header("Content-Type","application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body));
        var r=HttpClient.newHttpClient().send(b.build(),HttpResponse.BodyHandlers.ofString());
        var data=json.readTree(r.body());
        if(!path.equals("/user/login"))evidence.add("FRONT "+path+" HTTP="+r.statusCode()+" "+r.body());
        assertThat(r.statusCode()).isEqualTo(200);assertThat(data.path("code").asInt()).as(path).isEqualTo(expected);return data.path("data");
    }
    void audit(HttpClient c,long id,int status)throws Exception {
        assertThat(code(request(c,"/novel/bookComment/audit","id="+id+"&version="+version("book_comment",id)+"&status="+status))).isZero();
    }
    void raudit(HttpClient c,long id,int status)throws Exception {
        assertThat(code(request(c,"/novel/bookComment/replyAudit","id="+id+"&version="+version("book_comment_reply",id)+"&status="+status))).isZero();
    }
    void countInvariant(){
        assertThat(n("SELECT COUNT(*) FROM book b WHERE NOT(b.comment_count <=> (SELECT COUNT(*) FROM book_comment c WHERE c.book_id=b.id AND c.audit_status=1))")).isZero();
        assertThat(n("SELECT COUNT(*) FROM book_comment c WHERE NOT(c.reply_count <=> (SELECT COUNT(*) FROM book_comment_reply r WHERE r.comment_id=c.id AND r.audit_status=1))")).isZero();
    }
    void visible(long id,boolean expected)throws Exception {
        var list=front("/book/listCommentByPage?bookId="+book+"&curr=1&limit=100",null,200);
        boolean found=false;for(var row:list.path("list"))if(row.path("id").asLong()==id)found=true;
        assertThat(found).isEqualTo(expected);
        assertThat(list.path("total").asLong()).isEqualTo(n("SELECT comment_count FROM book WHERE id=?",book));
        var r=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(front+"/book/reply-"+id+".html?to=pc")).build(),HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).isEqualTo(expected?200:404);
    }
    void hiddenVotes(long id,long reply)throws Exception {
        for(String p:List.of("setCommentLike","setCommentUnLike","toggleCommentLike","toggleCommentUnLike"))
            front("/book/"+p,"commentId="+id+"&active=true",3003);
        for(String p:List.of("setReplyLike","setReplyUnLike","toggleReplyLike","toggleReplyUnLike"))
            front("/book/"+p,"replyId="+reply+"&active=true",3003);
    }
    @Test void actualLoginModerationReactionsAndPermissions() throws Exception {
        assertThat(System.getenv("CORRECTNESS_APP_JDBC")).contains("/cv_it_").contains("_app?");
        jdbc=new JdbcTemplate(new DriverManagerDataSource(System.getenv("CORRECTNESS_APP_JDBC"),System.getenv("CORRECTNESS_MYSQL_USER"),System.getenv("CORRECTNESS_MYSQL_PASSWORD")));
        assertThat(jdbc.queryForObject("SELECT label FROM correctness_identity WHERE id=1",String.class)).isEqualTo(System.getenv("CORRECTNESS_RUN_ID"));
        String pwd=System.getenv("CORRECTNESS_ADMIN_PASSWORD");assertThat(pwd).matches("[a-f0-9]{32}");
        try {
            for(int k=1;k<=2;k++)jdbc.update("INSERT INTO sys_user(user_id,username,name,password,dept_id,status) VALUES(?,?,?,?,0,1)",991900000L+k,"jointadmin"+k,"Joint verifier",MD5Utils.encrypt("jointadmin"+k,pwd));
            jdbc.update("INSERT INTO sys_role(role_id,role_name) VALUES(991900001,'joint verifier')");
            jdbc.update("INSERT INTO sys_menu(menu_id,parent_id,name,perms,type,order_num) VALUES(991900001,0,'joint verifier','novel:bookComment:bookComment,novel:bookComment:add,novel:bookComment:edit,novel:bookComment:remove,novel:bookComment:batchRemove,novel:book:edit',1,0)");
            jdbc.update("INSERT INTO sys_user_role(user_id,role_id) VALUES(991900001,991900001)");jdbc.update("INSERT INTO sys_role_menu(role_id,menu_id) VALUES(991900001,991900001)");
            var admin=login("jointadmin1",pwd);var denied=login("jointadmin2",pwd);
            var anon=request(client(),"/novel/bookComment/audit","id=1&version=0&status=1");assertThat(anon.statusCode()).isIn(302,401,403);
            for(String p:List.of("audit","replyAudit","replyEdit","replyRemove"))assertThat(code(request(denied,"/novel/bookComment/"+p,"id=1&version=0&status=1&content=test"))).isEqualTo(403);
            token=null;front("/book/setCommentLike","commentId=1&active=true",1001);
            var auth=front("/user/login","username=19900000001&password="+pwd,200);token=auth.path("token").asText();assertThat(token).isNotBlank();
            front("/book/addBookComment","bookId="+book+"&commentContent=joint-pending&auditStatus=1&replyCount=99&createUserId=1",200);
            long id=n("SELECT id FROM book_comment WHERE book_id=? AND create_user_id=?",book,owner);
            assertThat(n("SELECT audit_status FROM book_comment WHERE id=?",id)).isZero();assertThat(n("SELECT comment_count FROM book WHERE id=?",book)).isZero();visible(id,false);
            var own=front("/user/listCommentByPage?curr=1&limit=50",null,200);assertThat(own.toString()).contains("joint-pending");
            front("/book/setCommentLike","commentId="+id+"&active=true",3003);
            audit(admin,id,1);visible(id,true);countInvariant();long v=version("book_comment",id);
            audit(admin,id,1);assertThat(version("book_comment",id)).isEqualTo(v);assertThat(n("SELECT comment_count FROM book WHERE id=?",book)).isEqualTo(1);
            assertThat(code(request(admin,"/novel/bookComment/audit","id="+id+"&version=0&status=2"))).isEqualTo(3002);
            try(var redis=new Jedis("127.0.0.1",Integer.parseInt(System.getenv("JOINT_REDIS_PORT")))){
                redis.auth(System.getenv("CORRECTNESS_REDIS_PASSWORD"));String key="like:comment:"+id;
                for(int k=0;k<2;k++){var s=front("/book/setCommentLike","commentId="+id+"&active=true",200);assertThat(s.path("liked").asBoolean()).isTrue();assertThat(s.path("likesCount").asLong()).isEqualTo(1);}
                assertThat(redis.smembers(key)).containsExactly(String.valueOf(owner));
                front("/book/setCommentUnLike","commentId="+id+"&active=true",200);
                var s=front("/book/setCommentLike","commentId="+id+"&active=false",200);assertThat(s.path("liked").asBoolean()).isFalse();assertThat(s.path("disliked").asBoolean()).isTrue();assertThat(redis.scard(key)).isZero();
                var b=HttpRequest.newBuilder(URI.create(front+"/book/toggleCommentLike")).header("Authorization",token).header("Content-Type","application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString("commentId="+id)).build();
                var old=HttpClient.newHttpClient().send(b,HttpResponse.BodyHandlers.ofString());assertThat(old.headers().firstValue("Deprecation")).isPresent();assertThat(json.readTree(old.body()).path("data").asLong()).isEqualTo(1);evidence.add("legacy headers="+old.headers().map()+" body="+old.body());
                front("/book/addCommentReply","commentId="+id+"&replyContent=joint-reply",200);long reply=n("SELECT id FROM book_comment_reply WHERE comment_id=?",id);
                front("/book/setReplyLike","replyId="+reply+"&active=true",3003);assertThat(front("/book/listCommentReplyByPage?commentId="+id,null,200).path("total").asLong()).isZero();
                raudit(admin,reply,1);raudit(admin,reply,1);assertThat(front("/book/listCommentReplyByPage?commentId="+id,null,200).path("total").asLong()).isEqualTo(1);front("/book/setReplyLike","replyId="+reply+"&active=true",200);
                audit(admin,id,2);visible(id,false);hiddenVotes(id,reply);assertThat(front("/user/listReplyByPage?curr=1&limit=50",null,200).toString()).contains("joint-reply");assertThat(n("SELECT reply_count FROM book_comment WHERE id=?",id)).isEqualTo(1);countInvariant();
                audit(admin,id,1);visible(id,true);assertThat(front("/book/listCommentReplyByPage?commentId="+id,null,200).path("total").asLong()).isEqualTo(1);
                front("/book/editComment","id="+id+"&version="+version("book_comment",id)+"&content=joint-author-edited",200);visible(id,false);hiddenVotes(id,reply);countInvariant();
                audit(admin,id,2);front("/book/editComment","id="+id+"&version="+version("book_comment",id)+"&content=joint-resubmit-original",200);assertThat(n("SELECT COUNT(*) FROM book_comment WHERE book_id=? AND create_user_id=?",book,owner)).isEqualTo(1);assertThat(n("SELECT audit_status FROM book_comment WHERE id=?",id)).isZero();
                audit(admin,id,1);raudit(admin,reply,2);assertThat(n("SELECT reply_count FROM book_comment WHERE id=?",id)).isZero();raudit(admin,reply,1);
                front("/book/editReply","id="+reply+"&version="+version("book_comment_reply",reply)+"&content=reply-edited",200);assertThat(n("SELECT reply_count FROM book_comment WHERE id=?",id)).isZero();raudit(admin,reply,1);
                long before=n("SELECT comment_count FROM book WHERE id=?",book);assertThat(code(request(admin,"/novel/book/update","id="+book+"&bookName=JointEdited&commentCount=999"))).isZero();assertThat(n("SELECT comment_count FROM book WHERE id=?",book)).isEqualTo(before);
                assertThat(code(request(admin,"/novel/bookComment/remove","id="+id))).isZero();assertThat(code(request(admin,"/novel/bookComment/remove","id="+id))).isNotZero();assertThat(n("SELECT COUNT(*) FROM book_comment_reply WHERE comment_id=?",id)).isZero();visible(id,false);countInvariant();
                evidence.add("REDIS retained after deletion like="+redis.smembers(key)+" unlike="+redis.smembers("unlike:comment:"+id)+" reply="+redis.smembers("like:comment:reply:"+reply));
            }
            front("/book/addBookComment","bookId="+book+"&commentContent=browser-own-pending",200);
            // Retain seven approved comments for real browser paging and state tests.
            for(int k=0;k<7;k++){
                long uid=owner+10+k;jdbc.update("INSERT INTO user(id,username,password,nick_name,password_algorithm,token_version,create_time,update_time) VALUES(?,?,?,'browser fixture','MD5',0,NOW(),NOW())",uid,"jointfixture"+k,"00000000000000000000000000000000");
                assertThat(code(request(admin,"/novel/bookComment/save","bookId="+book+"&createUserId="+uid+"&commentContent=browser-fixture-"+k))).isZero();audit(admin,n("SELECT id FROM book_comment WHERE book_id=? AND create_user_id=?",book,uid),1);
            }
            countInvariant();
            long browserParent=n("SELECT MAX(id) FROM book_comment WHERE book_id=? AND audit_status=1",book);
            front("/book/addCommentReply","commentId="+browserParent+"&replyContent=browser-approved-reply",200);
            long browserReply=n("SELECT id FROM book_comment_reply WHERE comment_id=?",browserParent);raudit(admin,browserReply,1);
            Files.writeString(Path.of(System.getenv("CORRECTNESS_EVIDENCE"),"browser-fixture.json"),json.writeValueAsString(Map.of("bookId",String.valueOf(book),"commentId",String.valueOf(n("SELECT MAX(id) FROM book_comment WHERE book_id=?",book)),"replyId",String.valueOf(browserReply),"username","19900000001")));
        }finally{
            evidence.add("SQL book="+jdbc.queryForList("SELECT id,comment_count FROM book WHERE id=?",book));
            evidence.add("SQL comments="+jdbc.queryForList("SELECT id,audit_status,moderation_version,reply_count FROM book_comment WHERE book_id=?",book));
            Files.write(Path.of(System.getenv("CORRECTNESS_EVIDENCE"),"joint-http-sql-redis.txt"),evidence);
        }
    }
}
