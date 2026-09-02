package com.java2nb.novel.controller.page;

import com.java2nb.novel.core.utils.ThreadLocalUtil;
import com.java2nb.novel.entity.Book;
import com.java2nb.novel.entity.BookContent;
import com.java2nb.novel.entity.BookIndex;
import com.java2nb.novel.service.AuthorService;
import com.java2nb.novel.service.BookContentService;
import com.java2nb.novel.service.BookService;
import com.java2nb.novel.service.NewsService;
import com.java2nb.novel.service.UserService;
import com.java2nb.novel.vo.BookIndexNavigationVO;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.ui.ExtendedModelMap;

import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class PageControllerChapterTaskTest {

    @Test
    void chapterPageUsesThreeExecutorTasksWithoutChangingPageData() {
        BookService bookService = mock(BookService.class);
        UserService userService = mock(UserService.class);
        BookContentService contentService = mock(BookContentService.class);
        CountingDirectExecutor executor = new CountingDirectExecutor();

        Book book = new Book();
        book.setId(1L);
        BookIndex index = new BookIndex();
        index.setId(100L);
        index.setIndexNum(10);
        index.setIsVip((byte) 0);
        index.setStorageType("db");
        BookContent content = new BookContent();
        content.setIndexId(100L);
        content.setContent("chapter text");

        when(bookService.queryBookDetail(1L)).thenReturn(book);
        when(bookService.queryBookIndex(100L)).thenReturn(index);
        when(bookService.queryBookIndexNavigation(1L, 10))
            .thenReturn(new BookIndexNavigationVO(99L, 101L));
        when(contentService.queryBookContent(1L, 100L)).thenReturn(content);

        PageController controller = new PageController(
            bookService,
            mock(NewsService.class),
            mock(AuthorService.class),
            userService,
            executor,
            Map.of("db", contentService));
        ExtendedModelMap model = new ExtendedModelMap();

        try (MockedStatic<ThreadLocalUtil> threadLocal = mockStatic(ThreadLocalUtil.class)) {
            threadLocal.when(ThreadLocalUtil::getTemplateDir).thenReturn("");

            String view = controller.bookContent(1L, 100L, new MockHttpServletRequest(), model);

            assertThat(view).isEqualTo("book/book_content");
        }

        assertThat(executor.submittedTaskCount()).isEqualTo(3);
        assertThat(model.get("book")).isSameAs(book);
        assertThat(model.get("bookIndex")).isSameAs(index);
        assertThat(model.get("preBookIndexId")).isEqualTo(99L);
        assertThat(model.get("nextBookIndexId")).isEqualTo(101L);
        assertThat(model.get("bookContent")).isSameAs(content);
        assertThat(model.get("needBuy")).isEqualTo(false);
    }

    private static final class CountingDirectExecutor extends ThreadPoolExecutor {

        private int submittedTaskCount;

        private CountingDirectExecutor() {
            super(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
        }

        @Override
        public void execute(Runnable command) {
            submittedTaskCount++;
            command.run();
        }

        private int submittedTaskCount() {
            return submittedTaskCount;
        }
    }
}
