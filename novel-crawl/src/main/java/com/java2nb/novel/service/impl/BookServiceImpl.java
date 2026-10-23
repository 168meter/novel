package com.java2nb.novel.service.impl;

import com.java2nb.novel.entity.Book;
import com.java2nb.novel.cache.ChapterCacheIdentity;
import com.java2nb.novel.cache.CrawlChapterCacheInvalidator;
import com.java2nb.novel.entity.BookContent;
import com.java2nb.novel.entity.BookIndex;
import com.java2nb.novel.mapper.*;
import com.java2nb.novel.service.BookContentService;
import com.java2nb.novel.service.BookService;
import com.java2nb.novel.utils.Constants;
import lombok.RequiredArgsConstructor;
import org.mybatis.dynamic.sql.render.RenderingStrategies;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.LinkedHashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.java2nb.novel.mapper.BookDynamicSqlSupport.crawlBookId;
import static com.java2nb.novel.mapper.BookDynamicSqlSupport.crawlSourceId;
import static com.java2nb.novel.mapper.CrawlSourceDynamicSqlSupport.id;
import static org.mybatis.dynamic.sql.SqlBuilder.*;
import static org.mybatis.dynamic.sql.select.SelectDSL.select;

/**
 * @author Administrator
 */
@Service
@RequiredArgsConstructor
public class BookServiceImpl implements BookService {

    private final CrawlBookMapper bookMapper;

    private final BookCategoryMapper bookCategoryMapper;

    private final CrawlBookIndexMapper bookIndexMapper;

    private final Map<String, BookContentService> bookContentServiceMap;

    private final CrawlChapterCacheInvalidator chapterCacheInvalidator;

    @Value("${content.save.storage}")
    private String storageType;


    @Override
    public boolean queryIsExistByBookNameAndAuthorName(String bookName, String authorName) {

        return bookMapper.count(countFrom(BookDynamicSqlSupport.book).where(BookDynamicSqlSupport.bookName, isEqualTo(bookName))
                .and(BookDynamicSqlSupport.authorName, isEqualTo(authorName))
                .build()
                .render(RenderingStrategies.MYBATIS3)) > 0;

    }

    @Override
    public void updateCrawlProperties(Long id, Integer sourceId, String bookId) {
        bookMapper.update(update(BookDynamicSqlSupport.book)
                .set(crawlSourceId)
                .equalTo(sourceId)
                .set(crawlBookId)
                .equalTo(bookId)
                .where(BookDynamicSqlSupport.id, isEqualTo(id))
                .build()
                .render(RenderingStrategies.MYBATIS3));
    }

    @Override
    public String queryCatNameByCatId(int catId) {
        return bookCategoryMapper.selectMany(select(BookCategoryDynamicSqlSupport.name)
                .from(BookCategoryDynamicSqlSupport.bookCategory)
                .where(id, isEqualTo(catId))
                .build()
                .render(RenderingStrategies.MYBATIS3)).get(0).getName();
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public void saveBookAndIndexAndContent(Book book, List<BookIndex> bookIndexList, List<BookContent> bookContentList) {
        if (!queryIsExistByBookNameAndAuthorName(book.getBookName(), book.getAuthorName())) {

            if (bookIndexList.size() > 0) {

                //保存小说主表

                book.setCreateTime(new Date());
                bookMapper.insertSelective(book);

                //批量保存目录和内容
                bookIndexList.forEach(bookIndex -> {
                    bookIndex.setStorageType(storageType);
                });
                bookIndexMapper.insertMultiple(bookIndexList);
                bookContentServiceMap.get(storageType).saveBookContent(bookContentList, book.getId());
                if ("db".equals(storageType)) {
                    LinkedHashSet<ChapterCacheIdentity> identities = new LinkedHashSet<>();
                    for (BookIndex bookIndex : bookIndexList) {
                        identities.add(new ChapterCacheIdentity(book.getId(), bookIndex.getId()));
                    }
                    chapterCacheInvalidator.evictAfterCommit(List.copyOf(identities));
                }

            }
        }


    }

    @Override
    public List<Book> queryNeedUpdateBook(Date startDate, int limit) {
        List<Book> books = bookMapper.queryNeedUpdateBook(startDate, limit);
        if (books.size() > 0) {
            //更新最后抓取时间为当前时间
            bookMapper.updateCrawlLastTime(books, new Date());
        }
        return books;
    }

    @Override
    public Map<Integer, BookIndex> queryExistBookIndexMap(Long bookId) {
        List<BookIndex> bookIndexs = bookIndexMapper.selectMany(select(BookIndexDynamicSqlSupport.id, BookIndexDynamicSqlSupport.indexNum, BookIndexDynamicSqlSupport.indexName, BookIndexDynamicSqlSupport.wordCount, BookIndexDynamicSqlSupport.storageType)
                .from(BookIndexDynamicSqlSupport.bookIndex)
                .where(BookIndexDynamicSqlSupport.bookId, isEqualTo(bookId))
                .build()
                .render(RenderingStrategies.MYBATIS3));
        if (bookIndexs.size() > 0) {
            return bookIndexs.stream().collect(Collectors.toMap(BookIndex::getIndexNum, Function.identity()));
        }
        return new HashMap<>(0);
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public void updateBookAndIndexAndContent(Book book, List<BookIndex> bookIndexList, List<BookContent> bookContentList, Map<Integer, BookIndex> existBookIndexMap) {
        LinkedHashSet<ChapterCacheIdentity> identities = new LinkedHashSet<>();
        for (int i = 0; i < bookIndexList.size(); i++) {
            BookIndex bookIndex = bookIndexList.get(i);
            BookContent bookContent = bookContentList.get(i);


            if (!existBookIndexMap.containsKey(bookIndex.getIndexNum())) {
                //插入
                bookIndex.setStorageType(storageType);
                bookIndexMapper.insertSelective(bookIndex);
                bookContentServiceMap.get(storageType).saveBookContent(bookContent, book.getId());
                if ("db".equals(storageType)) {
                    identities.add(new ChapterCacheIdentity(book.getId(), bookIndex.getId()));
                }
            } else {
                //更新
                BookIndex existing = existBookIndexMap.get(bookIndex.getIndexNum());
                if (!Objects.equals(existing.getId(), bookIndex.getId())
                    || !Objects.equals(existing.getId(), bookContent.getIndexId())
                    || (bookIndex.getBookId() != null && !Objects.equals(book.getId(), bookIndex.getBookId()))
                    || (bookIndex.getStorageType() != null && !Objects.equals(existing.getStorageType(), bookIndex.getStorageType()))) {
                    throw new IllegalArgumentException("Existing chapter identity is immutable");
                }
                // Explicit editable columns: do not let a generic update rewrite book_id/storage_type.
                int affected = bookIndexMapper.update(update(BookIndexDynamicSqlSupport.bookIndex)
                    .set(BookIndexDynamicSqlSupport.indexNum).equalToWhenPresent(bookIndex.getIndexNum())
                    .set(BookIndexDynamicSqlSupport.indexName).equalToWhenPresent(bookIndex.getIndexName())
                    .set(BookIndexDynamicSqlSupport.wordCount).equalToWhenPresent(bookIndex.getWordCount())
                    .set(BookIndexDynamicSqlSupport.isVip).equalToWhenPresent(bookIndex.getIsVip())
                    .set(BookIndexDynamicSqlSupport.bookPrice).equalToWhenPresent(bookIndex.getBookPrice())
                    .set(BookIndexDynamicSqlSupport.createTime).equalToWhenPresent(bookIndex.getCreateTime())
                    .set(BookIndexDynamicSqlSupport.updateTime).equalToWhenPresent(bookIndex.getUpdateTime())
                    .where(BookIndexDynamicSqlSupport.id, isEqualTo(existing.getId()))
                    .and(BookIndexDynamicSqlSupport.bookId, isEqualTo(book.getId()))
                    .build().render(RenderingStrategies.MYBATIS3));
                if (affected != 1) throw new IllegalStateException("Chapter no longer exists in the expected book");
                bookContentServiceMap.get(existing.getStorageType()).updateBookContent(bookContent, book.getId());
                if ("db".equals(existing.getStorageType())) {
                    identities.add(new ChapterCacheIdentity(book.getId(), existing.getId()));
                }
            }


        }

        //更新小说主表
        book.setBookName(null);
        book.setAuthorName(null);
        if (Constants.VISIT_COUNT_DEFAULT.equals(book.getVisitCount())) {
            book.setVisitCount(null);
        }
        bookMapper.updateByPrimaryKeySelective(book);
        chapterCacheInvalidator.evictAfterCommit(List.copyOf(identities));

    }

    @Override
    public void updateCrawlLastTime(Long bookId) {
        Book book = new Book();
        book.setId(bookId);
        book.setCrawlLastTime(new Date());
        bookMapper.updateByPrimaryKeySelective(book);
    }

    @Override
    public Book queryBookByBookNameAndAuthorName(String bookName, String authorName) {
        List<Book> books = bookMapper.selectMany(select(BookDynamicSqlSupport.id).from(BookDynamicSqlSupport.book)
                .where(BookDynamicSqlSupport.bookName, isEqualTo(bookName))
                .and(BookDynamicSqlSupport.authorName, isEqualTo(authorName))
                .build()
                .render(RenderingStrategies.MYBATIS3));

        if (books.size() > 0) {
            return books.get(0);
        }

        return null;

    }

}
