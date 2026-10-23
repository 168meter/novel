package com.java2nb.novel.service.impl;

import com.java2nb.novel.cache.ChapterCacheIdentity;
import com.java2nb.novel.cache.AdminChapterCacheInvalidator;
import com.java2nb.novel.dao.BookContentDao;
import com.java2nb.novel.dao.BookDao;
import com.java2nb.novel.dao.BookIndexDao;
import com.java2nb.novel.domain.BookDO;
import com.java2nb.novel.service.BookService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;


@Service
@RequiredArgsConstructor
public class BookServiceImpl implements BookService {

    private final BookDao bookDao;
    private final BookIndexDao bookIndexDao;
    private final BookContentDao bookContentDao;
    private final AdminChapterCacheInvalidator chapterCacheInvalidator;

    @Override
    public BookDO get(Long id) {
        return bookDao.get(id);
    }

    @Override
    public List<BookDO> list(Map<String, Object> map) {
        return bookDao.list(map);
    }

    @Override
    public int count(Map<String, Object> map) {
        return bookDao.count(map);
    }

    @Override
    public int save(BookDO book) {
        book.setCommentCount(0);
        return bookDao.save(book);
    }

    @Override
    public int update(BookDO book) {
        book.setCommentCount(null);
        return bookDao.update(book);
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public int remove(Long id) {
        List<Long> indexIds = bookIndexDao.getIdsByBookId(id);
        if (!CollectionUtils.isEmpty(indexIds)) {
            Long[] indexIdArray = indexIds.toArray(new Long[0]);
            bookIndexDao.batchRemove(indexIdArray);
            bookContentDao.removeByIndexIds(indexIdArray);
            chapterCacheInvalidator.evictAfterCommit(indexIds.stream()
                .map(indexId -> new ChapterCacheIdentity(id, indexId))
                .toList());
        }
        return bookDao.remove(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int batchRemove(Long[] ids) {
        java.util.Set<ChapterCacheIdentity> identities = new java.util.LinkedHashSet<>();
        for (Long bookId : ids) {
            for (Long indexId : bookIndexDao.getIdsByBookId(bookId)) {
                identities.add(new ChapterCacheIdentity(bookId, indexId));
            }
        }
        int affected = bookDao.batchRemove(ids);
        // Preserve the existing batch-delete SQL/semantics; only add cache notification.
        if (affected > 0) chapterCacheInvalidator.evictAfterCommit(identities);
        return affected;
    }

    @Override
    public Map<Object, Object> tableSta(Date minDate) {
        List<Map<Object, Object>> maps = bookDao.tableSta(minDate);

        return maps.stream().collect(Collectors.toMap(x -> x.get("staDate"), x -> x.get("bookCount")));

    }

}
