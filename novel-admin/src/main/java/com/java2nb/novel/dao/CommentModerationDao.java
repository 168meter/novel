package com.java2nb.novel.dao;
import com.java2nb.novel.comment.ReplyRecord;
import org.apache.ibatis.annotations.*;
import java.util.*;
public interface CommentModerationDao extends com.java2nb.novel.comment.CommentModerationMapper {
    @Select({"<script>SELECT "+REPLY_COLUMNS+" FROM book_comment_reply <where><if test='status != null'>audit_status=#{status}</if><if test='commentId != null'>AND comment_id=#{commentId}</if></where> ORDER BY create_time DESC,id DESC LIMIT #{limit} OFFSET #{offset}</script>"})
    List<ReplyRecord> listReplies(@Param("status") Integer status,@Param("commentId") Long commentId,@Param("offset") int offset,@Param("limit") int limit);
    @Select({"<script>SELECT COUNT(*) FROM book_comment_reply <where><if test='status != null'>audit_status=#{status}</if><if test='commentId != null'>AND comment_id=#{commentId}</if></where></script>"})
    int countReplies(@Param("status") Integer status,@Param("commentId") Long commentId);
}
