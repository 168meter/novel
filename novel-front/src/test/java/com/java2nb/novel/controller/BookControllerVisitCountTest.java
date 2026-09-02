package com.java2nb.novel.controller;

import com.java2nb.novel.messaging.BookVisitEventPublisher;
import com.java2nb.novel.service.BookService;
import com.java2nb.novel.service.IpLocationService;
import com.java2nb.novel.service.LikeService;
import io.github.xxyopen.model.resp.RestResult;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class BookControllerVisitCountTest {

    @Test
    void publishesVisitWithoutUpdatingDatabaseSynchronously() {
        BookService bookService = mock(BookService.class);
        BookVisitEventPublisher publisher = mock(BookVisitEventPublisher.class);
        BookController controller = new BookController(
            bookService,
            Map.of(),
            mock(IpLocationService.class),
            mock(LikeService.class),
            publisher);

        RestResult<Void> result = controller.addVisitCount(42L);

        assertThat(result).isNotNull();
        verify(publisher).publish(42L);
        verify(bookService, never()).addVisitCount(anyLong(), anyInt());
    }
}
