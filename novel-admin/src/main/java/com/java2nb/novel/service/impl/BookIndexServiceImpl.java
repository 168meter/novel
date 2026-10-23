package com.java2nb.novel.service.impl;

import com.java2nb.novel.cache.AdminChapterCacheInvalidator;
import com.java2nb.novel.cache.ChapterCacheIdentity;
import com.java2nb.common.exception.BusinessException;
import com.java2nb.novel.dao.BookDao;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.Objects;

import com.java2nb.novel.dao.BookIndexDao;
import com.java2nb.novel.domain.BookIndexDO;
import com.java2nb.novel.service.BookIndexService;



@Service
@RequiredArgsConstructor
public class BookIndexServiceImpl implements BookIndexService {
	private final BookIndexDao bookIndexDao;
	private final AdminChapterCacheInvalidator chapterCacheInvalidator;
	private final BookDao bookDao;
	
	@Override
	public BookIndexDO get(Long id){
		return bookIndexDao.get(id);
	}
	
	@Override
	public List<BookIndexDO> list(Map<String, Object> map){
		return bookIndexDao.list(map);
	}
	
	@Override
	public int count(Map<String, Object> map){
		return bookIndexDao.count(map);
	}
	
	@Override
	@Transactional(rollbackFor = Exception.class)
	public int save(BookIndexDO bookIndex){
		if (bookIndex.getBookId() == null || bookIndex.getBookId() <= 0 || bookDao.get(bookIndex.getBookId()) == null) {
			throw new BusinessException(400, "book_id 必须关联已存在的书籍");
		}
		if (bookIndex.getStorageType() == null) bookIndex.setStorageType("db");
		if (!Set.of("db", "txt").contains(bookIndex.getStorageType())) {
			throw new BusinessException(400, "storage_type 仅支持 db 或 txt");
		}
		int affected = bookIndexDao.save(bookIndex);
		if (affected > 0) chapterCacheInvalidator.evictAfterCommit(identities(bookIndex));
		return affected;
	}
	
	@Override
	@Transactional(rollbackFor = Exception.class)
	public int update(BookIndexDO bookIndex){
		BookIndexDO before = bookIndexDao.get(bookIndex.getId());
		if (before == null) throw new BusinessException(404, "章节不存在");
		if (bookIndex.getBookId() != null && !Objects.equals(before.getBookId(), bookIndex.getBookId())) {
			throw new BusinessException(400, "已创建章节的 book_id 不可修改");
		}
		if (bookIndex.getStorageType() != null && !Objects.equals(before.getStorageType(), bookIndex.getStorageType())) {
			throw new BusinessException(400, "已创建章节的 storage_type 不可修改");
		}
		if (bookIndex.getIndexNum() == null && bookIndex.getIndexName() == null && bookIndex.getWordCount() == null
			&& bookIndex.getIsVip() == null && bookIndex.getBookPrice() == null
			&& bookIndex.getCreateTime() == null && bookIndex.getUpdateTime() == null) return 0;
		return bookIndexDao.update(bookIndex);
	}
	
	@Override
	@Transactional(rollbackFor = Exception.class)
	public int remove(Long id){
		BookIndexDO before = bookIndexDao.get(id);
		int affected = bookIndexDao.remove(id);
		if (affected > 0) chapterCacheInvalidator.evictAfterCommit(identities(before));
		return affected;
	}
	
	@Override
	@Transactional(rollbackFor = Exception.class)
	public int batchRemove(Long[] ids){
		Set<ChapterCacheIdentity> keys = new LinkedHashSet<>();
		for (Long id : ids) keys.addAll(identities(bookIndexDao.get(id)));
		int affected = bookIndexDao.batchRemove(ids);
		if (affected > 0) chapterCacheInvalidator.evictAfterCommit(keys);
		return affected;
	}

	private static Set<ChapterCacheIdentity> identities(BookIndexDO row) {
		Set<ChapterCacheIdentity> keys = new LinkedHashSet<>();
		if (row != null && row.getBookId() != null && row.getId() != null)
			keys.add(new ChapterCacheIdentity(row.getBookId(), row.getId()));
		return keys;
	}
	
}
