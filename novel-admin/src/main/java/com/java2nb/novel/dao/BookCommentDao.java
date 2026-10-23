package com.java2nb.novel.dao;

import com.java2nb.common.annotation.SanitizeMap;

import com.java2nb.novel.domain.BookCommentDO;

import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Delete;

/**
 * 小说评论表
 * @author xiongxy
 * @email 1179705413@qq.com
 * @date 2023-04-14 21:59:28
 */
@Mapper
public interface BookCommentDao {

	BookCommentDO get(Long id);
	
	List<BookCommentDO> list(@SanitizeMap Map<String,Object> map);
	
	int count(Map<String,Object> map);
	
	int save(BookCommentDO bookComment);
	
	int update(BookCommentDO bookComment);
	
	int remove(Long id);
	
	int batchRemove(Long[] ids);

    @Select("SELECT id FROM book WHERE id=#{value} FOR UPDATE")
    Long lockBook(Long bookId);

    @Select("SELECT id,book_id FROM book_comment WHERE id=#{value} FOR UPDATE")
    BookCommentDO lockComment(Long id);

    @Delete("DELETE FROM book_comment_reply WHERE comment_id=#{value}")
    int removeReplies(Long commentId);

    @Update("UPDATE book SET comment_count=GREATEST(COALESCE(comment_count,0)-1,0) WHERE id=#{value}")
    int decrementBookCommentCount(Long bookId);

    @Update("UPDATE book SET comment_count=COALESCE(comment_count,0)+1 WHERE id=#{value}")
    int incrementBookCommentCount(Long bookId);
}
