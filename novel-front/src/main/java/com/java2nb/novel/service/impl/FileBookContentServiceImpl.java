package com.java2nb.novel.service.impl;

import com.java2nb.novel.entity.BookContent;
import com.java2nb.novel.mapper.BookIndexMapper;
import com.java2nb.novel.service.BookContentService;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.FileReader;

@Service(value = "txt")
@RequiredArgsConstructor
public class FileBookContentServiceImpl implements BookContentService {
    private final BookIndexMapper bookIndexMapper;

    @Value("${content.save.path}")
    private String fileSavePath;

    @SneakyThrows
    @Override
    public BookContent queryBookContent(Long bookId, Long bookIndexId) {
        if (bookId == null || bookIndexId == null || bookId <= 0 || bookIndexId <= 0
            || bookIndexMapper.selectByPrimaryKey(bookIndexId)
                .filter(index -> bookId.equals(index.getBookId())).isEmpty()) {
            return null;
        }
        return loadContent(bookId, bookIndexId);
    }

    @Override
    public BookContent queryBookContent(com.java2nb.novel.service.BookService.OwnedChapter chapter) {
        return chapter == null ? null : loadContent(chapter.bookId(), chapter.chapterId());
    }

    @SneakyThrows
    private BookContent loadContent(Long bookId, Long bookIndexId) {
        try (BufferedReader in = new BufferedReader(new FileReader(fileSavePath + "/" + bookId + "/" + bookIndexId + ".txt"))) {
            StringBuilder sb = new StringBuilder();
            String str;
            while ((str = in.readLine()) != null) {
                sb.append(str);
            }
            BookContent result = new BookContent();
            result.setIndexId(bookIndexId);
            result.setContent(sb.toString());
            return result;
        }
    }
}
