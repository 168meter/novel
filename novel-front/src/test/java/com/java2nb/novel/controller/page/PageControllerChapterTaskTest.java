package com.java2nb.novel.controller.page;

import com.java2nb.novel.core.bean.UserDetails;
import com.java2nb.novel.core.utils.JwtTokenUtil;
import com.java2nb.novel.core.utils.ThreadLocalUtil;
import com.java2nb.novel.engagement.ReadingPageVisitRegistrar;
import com.java2nb.novel.entity.Book;
import com.java2nb.novel.entity.BookContent;
import com.java2nb.novel.entity.BookIndex;
import com.java2nb.novel.service.AuthorService;
import com.java2nb.novel.service.BookContentService;
import com.java2nb.novel.service.BookService;
import com.java2nb.novel.service.NewsService;
import com.java2nb.novel.service.UserService;
import com.java2nb.novel.vo.BookIndexNavigationVO;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.ui.ExtendedModelMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PageControllerChapterTaskTest {

    private static BookService booksWithOwnershipLookup() {
        BookService books = mock(BookService.class);
        org.mockito.Mockito.doCallRealMethod().when(books).findOwnedChapter(any(), any());
        return books;
    }


    @Test
    void mismatchedChapterIsRejectedBeforeNavigationContentPurchaseOrRegistration() {
        BookService books = booksWithOwnershipLookup();
        UserService users = mock(UserService.class);
        BookContentService contents = mock(BookContentService.class);
        ReadingPageVisitRegistrar registrar = mock(ReadingPageVisitRegistrar.class);
        when(books.findBookIndex(1L, 100L)).thenReturn(Optional.empty());
        PageController controller = new PageController(books, mock(NewsService.class), mock(AuthorService.class),
            users, new CountingDirectExecutor(), Map.of("db", contents), registrar, new SimpleMeterRegistry());
        ExtendedModelMap model = new ExtendedModelMap();
        assertThatThrownBy(() -> controller.bookContent(1L, 100L, new MockHttpServletRequest(), model))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
            .satisfies(error -> assertThat(((org.springframework.web.server.ResponseStatusException) error)
                .getStatusCode().value()).isEqualTo(404));
        verify(books, never()).queryBookIndexNavigation(any(), any());
        verify(contents, never()).queryBookContent(any(), any());
        verify(users, never()).queryIsBuyBookIndex(any(), any());
        verify(registrar, never()).register(any(), any(), any());
        assertThat(model).doesNotContainKey("readingPageVisitId");
    }

    @Test
    void absentOrForeignChapterReturnsHttp404EvenWithGlobalExceptionAdvice() throws Exception {
        BookService books = booksWithOwnershipLookup();
        when(books.findBookIndex(1L, 100L)).thenReturn(Optional.empty());
        ReadingPageVisitRegistrar registrar = mock(ReadingPageVisitRegistrar.class);
        PageController controller = new PageController(books, mock(NewsService.class), mock(AuthorService.class),
            mock(UserService.class), new CountingDirectExecutor(), Map.of(), registrar, new SimpleMeterRegistry());
        org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new com.java2nb.novel.core.advice.CommonExceptionHandler())
            .build().perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/book/1/100.html"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.view().name("404"));
        verify(registrar, never()).register(any(), any(), any());
    }

    @Test
    void nonexistentRequestedBookAlsoReturns404BeforeBookDetailLookup() throws Exception {
        BookService books = booksWithOwnershipLookup();
        when(books.findBookIndex(999L, 100L)).thenReturn(Optional.empty());
        ReadingPageVisitRegistrar registrar = mock(ReadingPageVisitRegistrar.class);
        PageController controller = new PageController(books, mock(NewsService.class), mock(AuthorService.class),
            mock(UserService.class), new CountingDirectExecutor(), Map.of(), registrar, new SimpleMeterRegistry());
        org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new com.java2nb.novel.core.advice.CommonExceptionHandler())
            .build().perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/book/999/100.html"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound());
        verify(books, never()).findBook(any());
        verify(books, never()).queryBookIndexNavigation(any(), any());
        verify(registrar, never()).register(any(), any(), any());
    }

    @Test
    void chapterPageUsesOneExecutorTaskWithoutChangingPageData() {
        BookService bookService = booksWithOwnershipLookup();
        UserService userService = mock(UserService.class);
        BookContentService contentService = mock(BookContentService.class);
        ReadingPageVisitRegistrar registrar = mock(ReadingPageVisitRegistrar.class);
        CountingDirectExecutor executor = new CountingDirectExecutor();

        Book book = new Book();
        book.setId(1L);
        BookIndex index = bookIndex((byte) 0);
        BookContent content = new BookContent();
        content.setIndexId(100L);
        content.setContent("chapter text");

        when(bookService.findBook(1L)).thenReturn(Optional.of(book));
        when(bookService.findBookIndex(1L, 100L)).thenReturn(Optional.of(index));
        when(bookService.queryBookIndexNavigation(1L, 10))
            .thenReturn(new BookIndexNavigationVO(99L, 101L));
        when(contentService.queryBookContent(any(BookService.OwnedChapter.class))).thenReturn(content);
        when(registrar.register("reader-mark", 1L, 100L)).thenReturn(Optional.of("page-token"));

        PageController controller = new PageController(
            bookService, mock(NewsService.class), mock(AuthorService.class), userService, executor,
            Map.of("db", contentService), registrar, new SimpleMeterRegistry());
        ExtendedModelMap model = new ExtendedModelMap();

        try (MockedStatic<ThreadLocalUtil> threadLocal = mockStatic(ThreadLocalUtil.class)) {
            threadLocal.when(ThreadLocalUtil::getTemplateDir).thenReturn("");
            threadLocal.when(ThreadLocalUtil::getClientId).thenReturn("reader-mark");

            String view = controller.bookContent(1L, 100L, new MockHttpServletRequest(), model);

            assertThat(view).isEqualTo("book/book_content");
        }

        assertThat(executor.submittedTaskCount()).isEqualTo(1);
        assertThat(model.get("book")).isSameAs(book);
        assertThat(model.get("bookIndex")).usingRecursiveComparison().isEqualTo(index);
        assertThat(model.get("preBookIndexId")).isEqualTo(99L);
        assertThat(model.get("nextBookIndexId")).isEqualTo(101L);
        assertThat(model.get("bookContent")).isSameAs(content);
        assertThat(model.get("needBuy")).isEqualTo(false);
        assertThat(model.get("readingPageVisitId")).isEqualTo("page-token");
        verify(registrar).register("reader-mark", 1L, 100L);
    }

    @Test
    void consolidatedTaskPreservesVipAccessDecisions() {
        BookService bookService = booksWithOwnershipLookup();
        UserService userService = mock(UserService.class);
        BookContentService contentService = mock(BookContentService.class);
        ReadingPageVisitRegistrar registrar = mock(ReadingPageVisitRegistrar.class);
        JwtTokenUtil jwtTokenUtil = mock(JwtTokenUtil.class);
        CountingDirectExecutor executor = new CountingDirectExecutor();

        Book book = new Book();
        book.setId(1L);
        BookIndex index = bookIndex((byte) 1);
        BookContent content = new BookContent();
        content.setContent("paid chapter text");
        UserDetails user = new UserDetails();
        user.setId(7L);

        when(bookService.findBook(1L)).thenReturn(Optional.of(book));
        when(bookService.findBookIndex(1L, 100L)).thenReturn(Optional.of(index));
        when(bookService.queryBookIndexNavigation(1L, 10))
            .thenReturn(new BookIndexNavigationVO(99L, 101L));
        when(contentService.queryBookContent(any(BookService.OwnedChapter.class))).thenReturn(content);
        when(registrar.register("reader-mark", 1L, 100L)).thenReturn(Optional.empty());
        when(jwtTokenUtil.getAuthenticatedUserDetails("token")).thenReturn(user);
        when(userService.queryIsBuyBookIndex(7L, 100L)).thenReturn(true);

        PageController controller = new PageController(
            bookService, mock(NewsService.class), mock(AuthorService.class), userService, executor,
            Map.of("db", contentService), registrar, new SimpleMeterRegistry());
        controller.setJwtTokenUtil(jwtTokenUtil);

        ExtendedModelMap anonymousModel = new ExtendedModelMap();
        ExtendedModelMap purchasedModel = new ExtendedModelMap();
        MockHttpServletRequest purchasedRequest = new MockHttpServletRequest();
        purchasedRequest.addHeader("Authorization", "token");

        try (MockedStatic<ThreadLocalUtil> threadLocal = mockStatic(ThreadLocalUtil.class)) {
            threadLocal.when(ThreadLocalUtil::getTemplateDir).thenReturn("");
            threadLocal.when(ThreadLocalUtil::getClientId).thenReturn("reader-mark");

            controller.bookContent(1L, 100L, new MockHttpServletRequest(), anonymousModel);
            controller.bookContent(1L, 100L, purchasedRequest, purchasedModel);
        }

        assertThat(anonymousModel.get("needBuy")).isEqualTo(true);
        assertThat(purchasedModel.get("needBuy")).isEqualTo(false);
        assertThat(executor.submittedTaskCount()).isEqualTo(2);
        verify(registrar).register("reader-mark", 1L, 100L);
    }

    @Test
    void lockedVipChapterDoesNotRegisterAPageVisit() {
        BookService bookService = booksWithOwnershipLookup();
        BookContentService contentService = mock(BookContentService.class);
        ReadingPageVisitRegistrar registrar = mock(ReadingPageVisitRegistrar.class);
        CountingDirectExecutor executor = new CountingDirectExecutor();
        BookIndex index = bookIndex((byte) 1);

        when(bookService.findBook(1L)).thenReturn(Optional.of(new Book()));
        when(bookService.findBookIndex(1L, 100L)).thenReturn(Optional.of(index));
        when(bookService.queryBookIndexNavigation(1L, 10)).thenReturn(new BookIndexNavigationVO(99L, 101L));
        BookContent content = new BookContent();
        content.setContent("locked chapter text");
        when(contentService.queryBookContent(any(BookService.OwnedChapter.class))).thenReturn(content);
        PageController controller = new PageController(bookService, mock(NewsService.class), mock(AuthorService.class),
            mock(UserService.class), executor, Map.of("db", contentService), registrar, new SimpleMeterRegistry());
        ExtendedModelMap model = new ExtendedModelMap();

        try (MockedStatic<ThreadLocalUtil> threadLocal = mockStatic(ThreadLocalUtil.class)) {
            threadLocal.when(ThreadLocalUtil::getTemplateDir).thenReturn("");
            controller.bookContent(1L, 100L, new MockHttpServletRequest(), model);
        }

        assertThat(model.get("needBuy")).isEqualTo(true);
        assertThat(model).doesNotContainKey("readingPageVisitId");
        verify(registrar, never()).register(any(), any(), any());
    }

    @Test
    void missingChapterContentReturns404AndDoesNotRegisterAPageVisit() {
        BookService bookService = booksWithOwnershipLookup();
        BookContentService contentService = mock(BookContentService.class);
        ReadingPageVisitRegistrar registrar = mock(ReadingPageVisitRegistrar.class);
        CountingDirectExecutor executor = new CountingDirectExecutor();
        BookIndex index = bookIndex((byte) 0);

        when(bookService.findBook(1L)).thenReturn(Optional.of(new Book()));
        when(bookService.findBookIndex(1L, 100L)).thenReturn(Optional.of(index));
        when(bookService.queryBookIndexNavigation(1L, 10)).thenReturn(new BookIndexNavigationVO(99L, 101L));
        when(contentService.queryBookContent(any(BookService.OwnedChapter.class))).thenReturn(null);
        PageController controller = new PageController(bookService, mock(NewsService.class), mock(AuthorService.class),
            mock(UserService.class), executor, Map.of("db", contentService), registrar, new SimpleMeterRegistry());
        ExtendedModelMap model = new ExtendedModelMap();

        try (MockedStatic<ThreadLocalUtil> threadLocal = mockStatic(ThreadLocalUtil.class)) {
            threadLocal.when(ThreadLocalUtil::getTemplateDir).thenReturn("");
            assertThatThrownBy(() -> controller.bookContent(
                1L, 100L, new MockHttpServletRequest(), model))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        }

        assertThat(model).doesNotContainKey("readingPageVisitId");
        verify(registrar, never()).register(any(), any(), any());
    }

    @Test
    void missingBookReturns404BeforeNavigationContentOrRegistration() {
        BookService bookService = booksWithOwnershipLookup();
        BookContentService contentService = mock(BookContentService.class);
        ReadingPageVisitRegistrar registrar = mock(ReadingPageVisitRegistrar.class);
        BookIndex index = bookIndex((byte) 0);
        when(bookService.findBookIndex(1L, 100L)).thenReturn(Optional.of(index));
        when(bookService.findBook(1L)).thenReturn(Optional.empty());
        PageController controller = new PageController(bookService, mock(NewsService.class), mock(AuthorService.class),
            mock(UserService.class), new CountingDirectExecutor(), Map.of("db", contentService), registrar,
            new SimpleMeterRegistry());

        assertThatThrownBy(() -> controller.bookContent(
            1L, 100L, new MockHttpServletRequest(), new ExtendedModelMap()))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        verify(bookService, never()).queryBookIndexNavigation(any(), any());
        verify(contentService, never()).queryBookContent(any(), any());
        verify(registrar, never()).register(any(), any(), any());
    }

    @Test
    void unknownStorageTypeReturns404WithoutCallingContentOrRegisteringVisit() {
        BookService bookService = booksWithOwnershipLookup();
        BookContentService contentService = mock(BookContentService.class);
        ReadingPageVisitRegistrar registrar = mock(ReadingPageVisitRegistrar.class);
        BookIndex index = bookIndex((byte) 0);
        index.setStorageType("s3");
        when(bookService.findBookIndex(1L, 100L)).thenReturn(Optional.of(index));
        when(bookService.findBook(1L)).thenReturn(Optional.of(new Book()));
        PageController controller = new PageController(bookService, mock(NewsService.class), mock(AuthorService.class),
            mock(UserService.class), new CountingDirectExecutor(), Map.of("db", contentService), registrar,
            new SimpleMeterRegistry());

        assertThatThrownBy(() -> controller.bookContent(
            1L, 100L, new MockHttpServletRequest(), new ExtendedModelMap()))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        verify(bookService, never()).queryBookIndexNavigation(any(), any());
        verify(contentService, never()).queryBookContent(any(), any());
        verify(registrar, never()).register(any(), any(), any());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.NullSource
    @org.junit.jupiter.params.provider.ValueSource(strings = {"", " ", "\t\r\n"})
    void nullEmptyOrBlankTextReturns404WithoutRegisteringVisit(String chapterText) {
        BookService bookService = booksWithOwnershipLookup();
        BookContentService contentService = mock(BookContentService.class);
        ReadingPageVisitRegistrar registrar = mock(ReadingPageVisitRegistrar.class);
        BookContent content = new BookContent();
        content.setContent(chapterText);
        when(bookService.findBookIndex(1L, 100L)).thenReturn(Optional.of(bookIndex((byte) 0)));
        when(bookService.findBook(1L)).thenReturn(Optional.of(new Book()));
        when(contentService.queryBookContent(any(BookService.OwnedChapter.class))).thenReturn(content);
        PageController controller = new PageController(bookService, mock(NewsService.class), mock(AuthorService.class),
            mock(UserService.class), new CountingDirectExecutor(), Map.of("db", contentService), registrar,
            new SimpleMeterRegistry());

        assertThatThrownBy(() -> controller.bookContent(
            1L, 100L, new MockHttpServletRequest(), new ExtendedModelMap()))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        verify(bookService, never()).queryBookIndexNavigation(any(), any());
        verify(registrar, never()).register(any(), any(), any());
    }

    private BookIndex bookIndex(byte vip) {
        BookIndex index = new BookIndex();
        index.setId(100L);
        index.setBookId(1L);
        index.setIndexNum(10);
        index.setIsVip(vip);
        index.setStorageType("db");
        return index;
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
