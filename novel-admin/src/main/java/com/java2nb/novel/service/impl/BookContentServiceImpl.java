package com.java2nb.novel.service.impl;

import com.java2nb.novel.cache.ChapterCacheIdentity;
import com.java2nb.common.exception.BusinessException;
import com.java2nb.novel.cache.AdminChapterCacheInvalidator;
import com.java2nb.novel.domain.BookIndexDO;
import com.java2nb.novel.domain.ChapterCacheIdentityDO;
import com.java2nb.novel.dao.BookIndexDao;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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
	@Transactional(rollbackFor = Exception.class)
	public int save(BookContentDO bookContent){
		ChapterCacheIdentity identity = identityForIndex(bookContent.getIndexId());
		int affected = bookContentDao.save(bookContent);
		if (affected > 0 && identity != null) {
			chapterCacheInvalidator.evictAfterCommit(identity.bookId(), identity.bookIndexId());
		}
		return affected;
	}
	
	@Override
	@Transactional(rollbackFor = Exception.class)
	public int update(BookContentDO bookContent){
		BookContentDO before = bookContentDao.get(bookContent.getId());
		if (before == null) throw new BusinessException(404, "正文不存在");
		if (bookContent.getIndexId() != null && !Objects.equals(before.getIndexId(), bookContent.getIndexId())) {
			throw new BusinessException(400, "已创建正文的 index_id 不可修改");
		}
		if (bookContent.getContent() == null) return 0;
		ChapterCacheIdentity identity = identityForIndex(before.getIndexId());
		int affected = bookContentDao.update(bookContent);
		if (affected > 0) chapterCacheInvalidator.evictAfterCommit(identity.bookId(), identity.bookIndexId());
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
		if (indexId == null || indexId <= 0) throw new BusinessException(400, "index_id 必须关联已存在的目录");
		BookIndexDO index = bookIndexDao.get(indexId);
		if (index == null || index.getBookId() == null || index.getBookId() <= 0) {
			throw new BusinessException(400, "index_id 必须关联已存在的目录");
		}
		return new ChapterCacheIdentity(index.getBookId(), indexId);
	}

	private LinkedHashSet<ChapterCacheIdentity> identitiesForRows(List<Long> rowIds) {
		LinkedHashSet<ChapterCacheIdentity> identities = new LinkedHashSet<>();
		for (ChapterCacheIdentityDO identity : bookContentDao.listCacheIdentitiesByIds(rowIds)) {
			identities.add(new ChapterCacheIdentity(identity.getBookId(), identity.getBookIndexId()));
		}
		return identities;
	}

}
