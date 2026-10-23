package com.java2nb.novel.service.impl;
import com.java2nb.novel.entity.BookIndex;
import com.java2nb.novel.mapper.BookIndexMapper;
import org.junit.jupiter.api.Test;
import java.util.Optional;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
class FileChapterOwnershipTest {
    @Test void mismatchedChapterIsRejectedBeforeFileAccess() {
        var mapper=mock(BookIndexMapper.class);var index=new BookIndex();index.setBookId(2L);
        when(mapper.selectByPrimaryKey(100L)).thenReturn(Optional.of(index));
        var service=new FileBookContentServiceImpl(mapper);
        assertThat(service.queryBookContent(1L,100L)).isNull();
        assertThat(service.queryBookContent(null,100L)).isNull();
    }
}
