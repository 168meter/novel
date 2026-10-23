package com.java2nb.novel.service;
import com.java2nb.novel.service.impl.BookServiceImpl;
import com.java2nb.novel.dao.*;
import com.java2nb.novel.domain.BookDO;
import com.java2nb.novel.cache.AdminChapterCacheInvalidator;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
class BookCommentCounterProtectionTest {
 @Test void ordinaryBookEditCannotOverwritePublicCommentCounter() {
  var dao=mock(BookDao.class);var service=new BookServiceImpl(dao,mock(BookIndexDao.class),mock(BookContentDao.class),null);
  var row=new BookDO();row.setId(1L);row.setCommentCount(99);service.update(row);
  var capture=org.mockito.ArgumentCaptor.forClass(BookDO.class);verify(dao).update(capture.capture());assertThat(capture.getValue().getCommentCount()).isNull();
 }
 @Test void newBookStartsWithZeroPublicComments() {
  var dao=mock(BookDao.class);var service=new BookServiceImpl(dao,mock(BookIndexDao.class),mock(BookContentDao.class),null);
  var row=new BookDO();row.setCommentCount(99);service.save(row);
  var capture=org.mockito.ArgumentCaptor.forClass(BookDO.class);verify(dao).save(capture.capture());assertThat(capture.getValue().getCommentCount()).isZero();
 }
}

