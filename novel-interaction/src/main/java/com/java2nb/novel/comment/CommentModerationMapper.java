package com.java2nb.novel.comment;

import org.apache.ibatis.annotations.*;
import java.util.List;

/** Shared SQL contract. Each application registers exactly one scan adapter. */
public interface CommentModerationMapper {
    String COMMENT_COLUMNS = "id,book_id AS bookId,comment_content AS commentContent,location,reply_count AS replyCount,audit_status AS auditStatus,create_time AS createTime,create_user_id AS createUserId,moderation_version AS moderationVersion";
    String REPLY_COLUMNS = "id,comment_id AS commentId,reply_content AS replyContent,location,audit_status AS auditStatus,create_time AS createTime,create_user_id AS createUserId,moderation_version AS moderationVersion";
    @Select("SELECT id FROM book WHERE id=#{id} FOR UPDATE") Long lockBook(Long id);
    @Select("SELECT " + COMMENT_COLUMNS + " FROM book_comment WHERE id=#{id}") CommentRecord findComment(Long id);
    @Select("SELECT " + COMMENT_COLUMNS + " FROM book_comment WHERE id=#{id} FOR UPDATE") CommentRecord lockComment(Long id);
    @Select("SELECT " + REPLY_COLUMNS + " FROM book_comment_reply WHERE id=#{id}") ReplyRecord findReply(Long id);
    @Select("SELECT " + REPLY_COLUMNS + " FROM book_comment_reply WHERE id=#{id} FOR UPDATE") ReplyRecord lockReply(Long id);
    @Select("SELECT COUNT(*) FROM book_comment WHERE book_id=#{bookId} AND create_user_id=#{userId}")
    int existingComment(@Param("bookId") Long bookId,@Param("userId") Long userId);
    @Insert("INSERT INTO book_comment(book_id,comment_content,location,reply_count,audit_status,create_time,create_user_id,moderation_version) VALUES(#{bookId},#{commentContent},#{location},0,0,CURRENT_TIMESTAMP,#{createUserId},0)")
    @Options(useGeneratedKeys=true,keyProperty="id") int insertComment(CommentRecord row);
    @Insert("INSERT INTO book_comment_reply(comment_id,reply_content,location,audit_status,create_time,create_user_id,moderation_version) VALUES(#{commentId},#{replyContent},#{location},0,CURRENT_TIMESTAMP,#{createUserId},0)")
    @Options(useGeneratedKeys=true,keyProperty="id") int insertReply(ReplyRecord row);
    @Update("UPDATE book_comment SET audit_status=#{status},moderation_version=moderation_version+1 WHERE id=#{id} AND moderation_version=#{version}")
    int auditComment(@Param("id") Long id,@Param("status") int status,@Param("version") long version);
    @Update("UPDATE book_comment_reply SET audit_status=#{status},moderation_version=moderation_version+1 WHERE id=#{id} AND moderation_version=#{version}")
    int auditReply(@Param("id") Long id,@Param("status") int status,@Param("version") long version);
    @Update("UPDATE book_comment SET comment_content=#{content},audit_status=0,moderation_version=moderation_version+1 WHERE id=#{id} AND moderation_version=#{version}")
    int editComment(@Param("id") Long id,@Param("content") String content,@Param("version") long version);
    @Update("UPDATE book_comment_reply SET reply_content=#{content},audit_status=0,moderation_version=moderation_version+1 WHERE id=#{id} AND moderation_version=#{version}")
    int editReply(@Param("id") Long id,@Param("content") String content,@Param("version") long version);
    @Update("UPDATE book SET comment_count=COALESCE(comment_count,0)+#{delta} WHERE id=#{id} AND COALESCE(comment_count,0)+#{delta}>=0")
    int changeBookCount(@Param("id") Long id,@Param("delta") int delta);
    @Update("UPDATE book_comment SET reply_count=COALESCE(reply_count,0)+#{delta} WHERE id=#{id} AND COALESCE(reply_count,0)+#{delta}>=0")
    int changeReplyCount(@Param("id") Long id,@Param("delta") int delta);
    @Delete("DELETE FROM book_comment_reply WHERE comment_id=#{id}") int removeReplies(Long id);
    @Delete("DELETE FROM book_comment WHERE id=#{id}") int removeComment(Long id);
    @Delete("DELETE FROM book_comment_reply WHERE id=#{id}") int removeReply(Long id);
    @Select({"<script>SELECT DISTINCT book_id FROM book_comment WHERE id IN <foreach collection='ids' item='id' open='(' close=')' separator=','>#{id}</foreach> ORDER BY book_id</script>"})
    List<Long> booksForComments(@Param("ids") List<Long> ids);
}
