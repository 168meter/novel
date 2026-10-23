package com.java2nb.novel.controller.page;

import com.java2nb.novel.core.advice.CommonExceptionHandler;
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
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.View;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PageControllerHttpSemanticsTest {

    private BookService books;
    private UserService users;
    private BookContentService contents;
    private ReadingPageVisitRegistrar registrar;
    private SimpleMeterRegistry metrics;

    @BeforeEach
    void setUp() {
        books = mock(BookService.class, org.mockito.Mockito.CALLS_REAL_METHODS);
        users = mock(UserService.class);
        contents = mock(BookContentService.class, org.mockito.Mockito.CALLS_REAL_METHODS);
        registrar = mock(ReadingPageVisitRegistrar.class);
        metrics = new SimpleMeterRegistry();
        ThreadLocalUtil.setTemplateDirOverride("");
        ThreadLocalUtil.setClientId("test-client");
    }

    @AfterEach
    void tearDown() {
        ThreadLocalUtil.clear();
        metrics.close();
    }

    @Test
    void missingBookReturnsHtml404WithErrorPage() throws Exception {
        when(books.findBook(999L)).thenReturn(Optional.empty());

        mockMvc(new DirectExecutor()).perform(get("/book/999.html"))
            .andExpect(status().isNotFound())
            .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
            .andExpect(content().string("error-page-marker"));
    }

    @Test
    void homeRejectedSubmissionReturns503() throws Exception {
        mockMvc(new RejectingExecutor()).perform(get("/"))
            .andExpect(status().isServiceUnavailable());
        org.assertj.core.api.Assertions.assertThat(metrics.counter("novel.home.request.rejected").count()).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(metrics.counter("novel.chapter.request.rejected").count()).isZero();
    }

    @Test
    void missingBookIndexPageReturnsHtml404WithErrorPage() throws Exception {
        when(books.findBook(999L)).thenReturn(Optional.empty());

        mockMvc(new DirectExecutor()).perform(get("/book/indexList-999.html"))
            .andExpect(status().isNotFound())
            .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
            .andExpect(content().string("error-page-marker"));
    }

    @Test
    void missingChapterReturnsHtml404WithErrorPage() throws Exception {
        when(books.findBookIndex(1L, 100L)).thenReturn(Optional.empty());

        assertChapterNotFound("/book/1/100.html");
    }

    @Test
    void chapterFromAnotherBookReturnsHtml404WithErrorPage() throws Exception {
        when(books.findBookIndex(1L, 200L)).thenReturn(Optional.empty());

        assertChapterNotFound("/book/1/200.html");
    }

    @Test
    void missingContentReturnsHtml404WithErrorPage() throws Exception {
        arrangeChapter((byte) 0, null);

        assertChapterNotFound("/book/1/100.html");
    }

    @Test
    void freeChapterReturnsHtml200WithContentMarker() throws Exception {
        arrangeChapter((byte) 0, "chapter text");

        mockMvc(new DirectExecutor()).perform(get("/book/1/100.html"))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
            .andExpect(content().string("contentIdHidden"));
    }

    @Test
    void cacheHitReusesThePageOwnershipLookupWithoutAnotherMapperCall() throws Exception {
        arrangeChapter((byte) 0, "chapter text");
        var indexes=mock(com.java2nb.novel.mapper.BookIndexMapper.class);
        BookIndex index=new BookIndex();index.setId(100L);index.setBookId(1L);
        index.setStorageType("db");index.setIndexNum(10);index.setIsVip((byte)0);
        when(indexes.selectByPrimaryKey(100L)).thenReturn(Optional.of(index));
        when(books.findBookIndex(1L,100L)).thenAnswer(invocation -> indexes.selectByPrimaryKey(100L));
        var cache=mock(com.java2nb.novel.service.cache.ChapterContentCache.class);
        BookContent body=new BookContent();body.setContent("chapter text");
        when(cache.getOrLoad(org.mockito.ArgumentMatchers.eq(1L),org.mockito.ArgumentMatchers.eq(100L),org.mockito.ArgumentMatchers.any())).thenReturn(body);
        contents=new com.java2nb.novel.service.impl.DbBookContentServiceImpl(
            mock(com.java2nb.novel.mapper.BookContentMapper.class),cache,indexes);
        mockMvc(new DirectExecutor()).perform(get("/book/1/100.html")).andExpect(status().isOk());
        org.mockito.Mockito.verify(indexes,org.mockito.Mockito.times(1)).selectByPrimaryKey(100L);
    }

    @Test
    void unpurchasedVipChapterReturnsHtml200WithPurchaseMarker() throws Exception {
        arrangeChapter((byte) 1, "locked chapter text");

        mockMvc(new DirectExecutor()).perform(get("/book/1/100.html"))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
            .andExpect(content().string("vip-purchase-marker"));
    }

    @Test
    void purchasedVipChapterReturnsHtml200WithContentMarker() throws Exception {
        arrangeChapter((byte) 1, "purchased chapter text");
        UserDetails reader = new UserDetails();
        reader.setId(7L);
        JwtTokenUtil tokens = mock(JwtTokenUtil.class);
        when(tokens.getAuthenticatedUserDetails("reader-token")).thenReturn(reader);
        when(users.queryIsBuyBookIndex(7L, 100L)).thenReturn(true);
        PageController controller = controller(new DirectExecutor());
        controller.setJwtTokenUtil(tokens);

        mockMvc(controller).perform(get("/book/1/100.html").header("Authorization", "reader-token"))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
            .andExpect(content().string("contentIdHidden"));
    }

    @Test
    void unexpectedChapterFailureReturnsHtml500WithErrorPage() throws Exception {
        when(books.findBookIndex(1L, 100L)).thenThrow(new IllegalStateException("private database detail"));

        mockMvc(new DirectExecutor()).perform(get("/book/1/100.html"))
            .andExpect(status().isInternalServerError())
            .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
            .andExpect(content().string("error-page-marker"));
    }

    @Test
    void rejectedChapterTaskReturnsHtml503AndIncrementsLowCardinalityMetric() throws Exception {
        mockMvc(new RejectingExecutor()).perform(get("/book/1/100.html"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
            .andExpect(content().string("error-page-marker"));

        org.assertj.core.api.Assertions.assertThat(
            metrics.counter("novel.chapter.request.rejected").count()).isEqualTo(1.0d);
    }

    @Test
    void rejectedBookDetailTaskReturnsHtml503AndIncrementsLowCardinalityMetric() throws Exception {
        mockMvc(new RejectingExecutor()).perform(get("/book/1.html"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
            .andExpect(content().string("error-page-marker"));

        org.assertj.core.api.Assertions.assertThat(
            metrics.counter("novel.chapter.request.rejected").count()).isEqualTo(1.0d);
    }

    private void assertChapterNotFound(String path) throws Exception {
        mockMvc(new DirectExecutor()).perform(get(path))
            .andExpect(status().isNotFound())
            .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
            .andExpect(content().string("error-page-marker"));
    }

    private void arrangeChapter(byte vip, String text) {
        Book book = new Book();
        book.setId(1L);
        BookIndex index = new BookIndex();
        index.setId(100L);
        index.setBookId(1L);
        index.setIndexNum(10);
        index.setIsVip(vip);
        index.setStorageType("db");
        BookContent content = text == null ? null : new BookContent();
        if (content != null) {
            content.setContent(text);
        }
        when(books.findBookIndex(1L, 100L)).thenReturn(Optional.of(index));
        when(books.findBook(1L)).thenReturn(Optional.of(book));
        when(books.queryBookIndexNavigation(1L, 10)).thenReturn(new BookIndexNavigationVO(0L, 0L));
        when(contents.queryBookContent(1L, 100L)).thenReturn(content);
    }

    private MockMvc mockMvc(ThreadPoolExecutor executor) {
        return mockMvc(controller(executor));
    }

    private MockMvc mockMvc(PageController controller) {
        return MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new CommonExceptionHandler())
            .setViewResolvers((viewName, locale) -> pageView(viewName))
            .build();
    }

    private PageController controller(ThreadPoolExecutor executor) {
        return new PageController(books, mock(NewsService.class), mock(AuthorService.class), users, executor,
            Map.of("db", contents), registrar, metrics);
    }

    private View pageView(String viewName) {
        return (model, request, response) -> {
            response.setContentType(MediaType.TEXT_HTML_VALUE);
            if ("404".equals(viewName)) {
                response.getWriter().write("error-page-marker");
            } else if (Boolean.TRUE.equals(model.get("needBuy"))) {
                response.getWriter().write("vip-purchase-marker");
            } else {
                response.getWriter().write("contentIdHidden");
            }
        };
    }

    private static class DirectExecutor extends ThreadPoolExecutor {

        private DirectExecutor() {
            super(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
        }

        @Override
        public void execute(Runnable command) {
            command.run();
        }
    }

    private static final class RejectingExecutor extends DirectExecutor {

        @Override
        public void execute(Runnable command) {
            throw new RejectedExecutionException("test overload");
        }
    }
}
