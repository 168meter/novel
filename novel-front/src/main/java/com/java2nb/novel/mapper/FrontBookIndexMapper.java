package com.java2nb.novel.mapper;

import com.java2nb.novel.vo.BookIndexNavigationVO;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

public interface FrontBookIndexMapper extends BookIndexMapper {

    @Select("""
        SELECT
            COALESCE((
                SELECT id FROM book_index
                WHERE book_id = #{bookId} AND index_num < #{indexNum}
                ORDER BY index_num DESC LIMIT 1
            ), 0) AS pre_book_index_id,
            COALESCE((
                SELECT id FROM book_index
                WHERE book_id = #{bookId} AND index_num > #{indexNum}
                ORDER BY index_num LIMIT 1
            ), 0) AS next_book_index_id
        """)
    @Results({
        @Result(column = "pre_book_index_id", property = "preBookIndexId"),
        @Result(column = "next_book_index_id", property = "nextBookIndexId")
    })
    BookIndexNavigationVO queryNavigation(
        @Param("bookId") Long bookId,
        @Param("indexNum") Integer indexNum);
}
