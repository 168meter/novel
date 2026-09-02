package com.java2nb.novel.messaging;

import com.java2nb.novel.mapper.FrontBookMapper;
import java.lang.reflect.Method;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

class BookVisitBatchWriterTest {

    @Test
    void writesOneIncrementPerDistinctBook() {
        FrontBookMapper mapper = mock(FrontBookMapper.class);
        BookVisitBatchWriter writer = new BookVisitBatchWriter(mapper);

        writer.write(Map.of(1L, 3L, 2L, 1L));

        verify(mapper).addVisitCount(1L, 3L);
        verify(mapper).addVisitCount(2L, 1L);
        verifyNoMoreInteractions(mapper);
    }

    @Test
    void writeUsesOneTransactionForTheWholeBatch() throws NoSuchMethodException {
        Method write = BookVisitBatchWriter.class.getMethod("write", Map.class);

        Transactional transactional = write.getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.rollbackFor()).contains(Exception.class);
    }
}
