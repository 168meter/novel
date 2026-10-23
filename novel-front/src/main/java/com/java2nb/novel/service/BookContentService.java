package com.java2nb.novel.service;

import com.java2nb.novel.entity.BookContent;

import java.util.List;

public interface BookContentService {

    BookContent queryBookContent(Long bookId, Long bookIndexId);

    /** Reuses a server-issued ownership result; legacy direct calls still verify their two IDs. */
    default BookContent queryBookContent(BookService.OwnedChapter chapter) {
        return chapter == null ? null : queryBookContent(chapter.bookId(), chapter.chapterId());
    }

}
