package com.java2nb.novel.acceptance;

import com.java2nb.novel.cache.CrawlChapterCacheInvalidator;
import com.java2nb.novel.entity.*;
import com.java2nb.novel.mapper.*;
import com.java2nb.novel.service.BookContentService;
import com.java2nb.novel.service.impl.*;
import java.nio.file.*;
import java.util.*;
import org.springframework.test.util.ReflectionTestUtils;
import static com.java2nb.novel.acceptance.ChapterFinalSupport.*;

/** Real Crawl services, MySQL mappers, DB/TXT writers and afterCommit invalidation. */
public final class ChapterFinalCrawl {
    public static void main(String[] args) throws Exception {
        if(args.length==1 && args[0].equals("probe")){ChapterFinalSupport.probe();return;}
        check(args.length==1 && args[0].equals("run"),"Specify run");
        try(var s=new ChapterFinalSupport(false)) {
            var sessions=s.sessions(new String[]{"mybatis/mapping/BookMapper.xml","mybatis/mapping/BookIndexMapper.xml"});
            var indexes=s.mapper(sessions,CrawlBookIndexMapper.class);
            var db=new DbBookContentServiceImpl(s.mapper(sessions,BookContentMapper.class));
            var txt=new FileBookContentServiceImpl();ReflectionTestUtils.setField(txt,"fileSavePath",s.out.resolve("txt-root").toString());
            var target=new BookServiceImpl(s.mapper(sessions,CrawlBookMapper.class),s.mapper(sessions,BookCategoryMapper.class),indexes,
                Map.<String,BookContentService>of("db",db,"txt",txt),new CrawlChapterCacheInvalidator(s.redis,s.metrics));
            ReflectionTestUtils.setField(target,"storageType","db");var service=s.bean(BookServiceImpl.class,target);
            var book=new Book();book.setId(s.book);book.setWordCount(40);
            var existing=service.queryExistBookIndexMap(s.book);
            check(existing.get(6)!=null && "db".equals(existing.get(6).getStorageType()),"Missing DB fixture");
            check(s.redis.opsForValue().get(s.key(6)).contains("old"),"Crawl DB baseline not cached");
            var patch=new BookIndex();patch.setId(s.index(6));patch.setBookId(s.book);patch.setStorageType("db");patch.setIndexNum(6);patch.setIndexName("crawl title");patch.setWordCount(9);patch.setUpdateTime(new Date());
            var body=new BookContent();body.setIndexId(s.index(6));body.setContent("crawl new");
            s.save("crawl-before.json",s.snapshot(6));
            service.updateBookAndIndexAndContent(book,List.of(patch),List.of(body),existing);
            check("crawl new".equals(s.bodyText(6)),"Crawl production DB writer failed");
            check(!Boolean.TRUE.equals(s.redis.hasKey(s.key(6))),"Crawl afterCommit did not invalidate");
            s.save("crawl-after.json",s.snapshot(6));s.pass("crawl-regression",Map.of("storageType","db","bookId",s.book));

            check(existing.get(8)!=null && "txt".equals(existing.get(8).getStorageType()),"Missing TXT fixture");
            var text=new BookContent();text.setIndexId(s.index(8));text.setContent("txt old");txt.saveBookContent(text,s.book);
            var incoming=new BookIndex();incoming.setId(s.index(8));incoming.setIndexNum(8);incoming.setIndexName("txt title");incoming.setWordCount(7);incoming.setUpdateTime(new Date());
            text.setContent("txt new");
            service.updateBookAndIndexAndContent(book,List.of(incoming),List.of(text),existing);
            String value=Files.readString(s.out.resolve("txt-root").resolve(Long.toString(s.book)).resolve(s.index(8)+".txt"));
            check("txt new".equals(value),"TXT writer result wrong");
            check("txt".equals(s.jdbc.queryForObject("SELECT storage_type FROM book_index WHERE id=?",String.class,s.index(8))),"Storage identity changed");
            s.pass("txt-write",Map.of("storageType","txt","body",value));s.save("crawl-txt-after.json",s.snapshot(8));
        }
    }
}

