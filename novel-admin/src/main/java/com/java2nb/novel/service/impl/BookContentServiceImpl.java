package com.java2nb.novel.service.impl;

import com.java2nb.novel.cache.ChapterCacheIdentity;
import com.java2nb.novel.cache.AdminChapterCacheInvalidator;
import com.java2nb.novel.domain.BookIndexDO;
import com.java2nb.novel.domain.ChapterCacheIdentityDO;
import com.java2nb.novel.dao.BookIndexDao;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import com.java2nb.novel.dao.BookContentDao;
import com.java2nb.novel.domain.BookContentDO;
import com.java2nb.novel.service.BookContentService;



@Service
@RequiredArgsConstructor
public class BookContentServiceImpl implements BookContentService {
	private final BookContentDao bookContentDao;
	private final BookIndexDao bookIndexDao;
	private final AdminChapterCacheInvalidator chapterCacheInvalidator;
	
	@Override
	public BookContentDO get(Long id){
		return bookContentDao.get(id);
	}
	
	@Override
	public List<BookContentDO> list(Map<String, Object> map){
		return bookContentDao.list(map);
	}
	
	@Override
	public int count(Map<String, Object> map){
		return bookContentDao.count(map);
	}
	
	@Override
	public int save(BookContentDO bookContent){
		ChapterCacheIdentity identity = identityForIndex(bookContent.getIndexId());
		int affected = bookContentDao.save(bookContent);
		if (affected > 0 && identity != null) {
			chapterCacheInvalidator.evictAfterCommit(identity.bookId(), identity.bookIndexId());
		}
		return affected;
	}
	
	@Override
	public int update(BookContentDO bookContent){
		LinkedHashSet<ChapterCacheIdentity> identities = identitiesForRows(List.of(bookContent.getId()));
		if (bookContent.getIndexId() != null) {
			ChapterCacheIdentity newIdentity = identityForIndex(bookContent.getIndexId());
			if (newIdentity != null) identities.add(newIdentity);
		}
		int affected = bookContentDao.update(bookContent);
		if (affected > 0) chapterCacheInvalidator.evictAfterCommit(List.copyOf(identities));
		return affected;
	}
	
	@Override
	public int remove(Long id){
		List<ChapterCacheIdentity> identities = List.copyOf(identitiesForRows(List.of(id)));
		int affected = bookContentDao.remove(id);
		if (affected > 0) chapterCacheInvalidator.evictAfterCommit(identities);
		return affected;
	}
	
	@Override
	public int batchRemove(Long[] ids){
		List<Long> rowIds = Arrays.asList(ids);
		List<ChapterCacheIdentity> identities = List.copyOf(identitiesForRows(rowIds));
		int affected = bookContentDao.batchRemove(ids);
		if (affected > 0) chapterCacheInvalidator.evictAfterCommit(identities);
		return affected;
	}

	@Override
	public List<BookContentDO> listByIndexIds(List<Long> indexIds) {
		return bookContentDao.listByIndexIds(indexIds);
	}

	private ChapterCacheIdentity identityForIndex(Long indexId) {
		if (indexId == null) return null;
		BookIndexDO index = bookIndexDao.get(indexId);
		return index == null ? null : new ChapterCacheIdentity(index.getBookId(), indexId);
	}

	private LinkedHashSet<ChapterCacheIdentity> identitiesForRows(List<Long> rowIds) {
		LinkedHashSet<ChapterCacheIdentity> identities = new LinkedHashSet<>();
		for (ChapterCacheIdentityDO identity : bookContentDao.listCacheIdentitiesByIds(rowIds)) {
			identities.add(new ChapterCacheIdentity(identity.getBookId(), identity.getBookIndexId()));
		}
		return identities;
	}

}
