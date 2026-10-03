package com.java2nb.novel.controller.page;

import com.java2nb.novel.controller.BaseController;
import com.java2nb.novel.core.bean.UserDetails;
import com.java2nb.novel.core.utils.ThreadLocalUtil;
import com.java2nb.novel.engagement.ReadingPageVisitRegistrar;
import com.java2nb.novel.entity.*;
import com.java2nb.novel.service.*;
import com.java2nb.novel.vo.BookCommentVO;
import com.java2nb.novel.vo.BookIndexNavigationVO;
import com.java2nb.novel.vo.BookSettingVO;
import io.github.xxyopen.model.page.PageBean;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Controller;
import org.springframework.http.HttpStatus;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.ModelAndView;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.function.Supplier;

/**
 * @author 11797
 */
@Slf4j
@RequiredArgsConstructor
@Controller
public class PageController extends BaseController {

    private final BookService bookService;

    private final NewsService newsService;

    private final AuthorService authorService;

    private final UserService userService;

    private final ThreadPoolExecutor threadPoolExecutor;

    private final Map<String, BookContentService> bookContentServiceMap;

    private final ReadingPageVisitRegistrar readingPageVisitRegistrar;

    private final MeterRegistry meterRegistry;

    @RequestMapping("{url}.html")
    public String module(@PathVariable("url") String url) {
        return url;
    }

    @RequestMapping("{module}/{url}.html")
    public String module2(@PathVariable("module") String module, @PathVariable("url") String url,
        HttpServletRequest request) {

        if (request.getRequestURI().startsWith("/author")) {
            //访问作者专区
            UserDetails user = getUserDetails(request);
            if (user == null) {
                //未登录
                return "redirect:/user/login.html?originUrl=" + request.getRequestURI();
            }

            boolean isAuthor = authorService.isAuthor(user.getId());
            if (!isAuthor) {
                return "redirect:/author/register.html";
            }
        }

        return module + "/" + url;
    }

    @RequestMapping("{module}/{classify}/{url}.html")
    public String module3(@PathVariable("module") String module, @PathVariable("classify") String classify,
        @PathVariable("url") String url) {
        return module + "/" + classify + "/" + url;
    }

    /**
     * 首页
     */
    @SneakyThrows
    @RequestMapping(path = {"/", "/index", "/index.html"})
    public String index(Model model) {
        //加载小说首页小说基本信息线程
        CompletableFuture<Map<String, List<BookSettingVO>>> bookCompletableFuture = CompletableFuture.supplyAsync(
            bookService::listBookSettingVO, threadPoolExecutor);
        //加载首页新闻线程
        CompletableFuture<List<News>> newsCompletableFuture = CompletableFuture.supplyAsync(newsService::listIndexNews,
            threadPoolExecutor);
        model.addAttribute("bookMap", bookCompletableFuture.get());
        model.addAttribute("newsList", newsCompletableFuture.get());
        return ThreadLocalUtil.getTemplateDir() + "index";
    }

    /**
     * 登录页
     */
    @RequestMapping("user/login.html")
    public String login() {
        return ThreadLocalUtil.getTemplateDir() + "user/login";
    }

    /**
     * 注册页
     */
    @RequestMapping("user/register.html")
    public String register() {
        return ThreadLocalUtil.getTemplateDir() + "user/register";
    }

    /**
     * 忘记密码页
     */
    @RequestMapping(path = "user/forgot_password.html")
    public String forgotPassword() {
        return ThreadLocalUtil.getTemplateDir() + "user/forgot_password";
    }

    /**
     * 用户中心页
     */
    @RequestMapping("user/userinfo.html")
    public String userinfo() {
        return ThreadLocalUtil.getTemplateDir() + "user/userinfo";
    }

    /**
     * 我的书架页
     */
    @RequestMapping("user/favorites.html")
    public String favorites() {
        return ThreadLocalUtil.getTemplateDir() + "user/favorites";
    }

    /**
     * 阅读历史页
     */
    @RequestMapping("user/read_history.html")
    public String readHistory() {
        return ThreadLocalUtil.getTemplateDir() + "user/read_history";
    }

    /**
     * 充值页
     */
    @RequestMapping("pay/index.html")
    public String pay() {
        return ThreadLocalUtil.getTemplateDir() + "pay/index.html";
    }


    /**
     * 作品页
     */
    @RequestMapping("book/bookclass.html")
    public String bookClass() {
        return "book/bookclass";
    }

    /**
     * 排行页
     */
    @RequestMapping("book/book_ranking.html")
    public String bookRank() {

        return ThreadLocalUtil.getTemplateDir() + "book/book_ranking";
    }


    /**
     * 详情页
     */
    @SneakyThrows
    @RequestMapping("/book/{bookId}.html")
    public String bookDetail(@PathVariable("bookId") Long bookId, Model model) {
        //加载小说基本信息线程
        CompletableFuture<Book> bookCompletableFuture = submitReadingPageTask(() -> {
            //查询书籍
            Book book = bookService.findBook(bookId).orElse(null);
            log.debug("加载小说基本信息线程结束");
            return book;
        });
        //加载小说评论列表线程
        CompletableFuture<PageBean<BookCommentVO>> bookCommentPageBeanCompletableFuture = submitReadingPageTask(
            () -> {
                PageBean<BookCommentVO> bookCommentVOPageBean = bookService.listCommentByPage(null, bookId, 1, 5);
                log.debug("加载小说评论列表线程结束");
                return bookCommentVOPageBean;
            });
        //加载小说首章信息线程，该线程在加载小说基本信息线程执行完毕后才执行
        CompletableFuture<Long> firstBookIndexIdCompletableFuture = bookCompletableFuture.thenApplyAsync((book) -> {
            if (book == null) {
                return null;
            }
            if (book.getLastIndexId() != null) {
                //查询首章目录ID
                Long firstBookIndexId = bookService.queryFirstBookIndexId(bookId);
                log.debug("加载小说基本信息线程结束");
                return firstBookIndexId;
            }
            return null;
        }, threadPoolExecutor);
        //加载随机推荐小说线程，该线程在加载小说基本信息线程执行完毕后才执行
        CompletableFuture<List<Book>> recBookCompletableFuture = bookCompletableFuture.thenApplyAsync((book) -> {
            if (book == null) {
                return List.of();
            }
            List<Book> books = bookService.listRecBookByCatId(book.getCatId());
            log.debug("加载随机推荐小说线程结束");
            return books;
        }, threadPoolExecutor);

        Book book = awaitReadingPageTask(bookCompletableFuture);
        if (book == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Book not found");
        }
        model.addAttribute("book", book);
        model.addAttribute("firstBookIndexId", awaitReadingPageTask(firstBookIndexIdCompletableFuture));
        model.addAttribute("recBooks", awaitReadingPageTask(recBookCompletableFuture));
        model.addAttribute("bookCommentPageBean", awaitReadingPageTask(bookCommentPageBeanCompletableFuture));

        return ThreadLocalUtil.getTemplateDir() + "book/book_detail";
    }

    /**
     * 目录页
     */
    @SneakyThrows
    @RequestMapping("/book/indexList-{bookId}.html")
    public String indexList(@PathVariable("bookId") Long bookId, Model model) {
        Book book = bookService.findBook(bookId).orElse(null);
        if (book == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Book not found");
        }
        model.addAttribute("book", book);
        List<BookIndex> bookIndexList = bookService.queryIndexList(bookId, null, 1, null);
        model.addAttribute("bookIndexList", bookIndexList);
        model.addAttribute("bookIndexCount", bookIndexList.size());
        return ThreadLocalUtil.getTemplateDir() + "book/book_index";
    }

    /**
     * 内容页
     */
    @SneakyThrows
    @RequestMapping("/book/{bookId}/{bookIndexId}.html")
    public String bookContent(@PathVariable("bookId") Long bookId, @PathVariable("bookIndexId") Long bookIndexId,
        HttpServletRequest request, Model model) {
        // 每个章节请求只占用一个线程池任务，避免高并发下单个请求放大为多个排队任务
        CompletableFuture<ChapterPageResult> chapterPageDataCompletableFuture = submitReadingPageTask(() -> {
            BookIndex bookIndex = bookService.findBookIndex(bookId, bookIndexId).orElse(null);
            if (bookIndex == null) {
                return ChapterPageResult.failure(ChapterPageFailure.CHAPTER_NOT_FOUND);
            }
            Book book = bookService.findBook(bookId).orElse(null);
            if (book == null) {
                return ChapterPageResult.failure(ChapterPageFailure.BOOK_NOT_FOUND);
            }

            String storageType = bookIndex.getStorageType();
            if (!("db".equals(storageType) || "txt".equals(storageType))) {
                return ChapterPageResult.failure(ChapterPageFailure.INVALID_STORAGE_TYPE);
            }
            BookContentService bookContentService = bookContentServiceMap.get(storageType);
            if (bookContentService == null) {
                return ChapterPageResult.failure(ChapterPageFailure.INVALID_STORAGE_TYPE);
            }
            BookContent bookContent = bookContentService.queryBookContent(bookId, bookIndexId);
            if (bookContent == null || StringUtils.isBlank(bookContent.getContent())) {
                return ChapterPageResult.failure(ChapterPageFailure.CONTENT_NOT_FOUND);
            }

            BookIndexNavigationVO navigation =
                bookService.queryBookIndexNavigation(bookId, bookIndex.getIndexNum());

            boolean needBuy = false;
            if (bookIndex.getIsVip() != null && bookIndex.getIsVip() == 1) {
                UserDetails user = getUserDetails(request);
                if (user == null) {
                    needBuy = true;
                } else {
                    needBuy = !userService.queryIsBuyBookIndex(user.getId(), bookIndexId);
                }
            }

            log.debug("加载章节页面数据线程结束");
            return ChapterPageResult.success(new ChapterPageData(book, bookIndex, navigation.getPreBookIndexId(),
                navigation.getNextBookIndexId(), bookContent, needBuy));
        });

        ChapterPageResult chapterPageResult = awaitReadingPageTask(chapterPageDataCompletableFuture);
        if (chapterPageResult.failure() != null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, chapterPageResult.failure().message());
        }
        ChapterPageData chapterPageData = chapterPageResult.data();
        model.addAttribute("book", chapterPageData.book());
        model.addAttribute("bookIndex", chapterPageData.bookIndex());
        model.addAttribute("preBookIndexId", chapterPageData.preBookIndexId());
        model.addAttribute("nextBookIndexId", chapterPageData.nextBookIndexId());
        model.addAttribute("bookContent", chapterPageData.bookContent());
        model.addAttribute("needBuy", chapterPageData.needBuy());

        if (!chapterPageData.needBuy()) {
            readingPageVisitRegistrar.register(ThreadLocalUtil.getClientId(), bookId, bookIndexId)
                .ifPresent(pageVisitId -> model.addAttribute("readingPageVisitId", pageVisitId));
        }

        return ThreadLocalUtil.getTemplateDir() + "book/book_content";
    }

    private <T> CompletableFuture<T> submitReadingPageTask(Supplier<T> task) {
        try {
            return CompletableFuture.supplyAsync(task, threadPoolExecutor);
        } catch (RejectedExecutionException exception) {
            throw readingPageOverloaded(exception);
        }
    }

    private <T> T awaitReadingPageTask(CompletableFuture<T> task)
        throws ExecutionException, InterruptedException {
        try {
            return task.get();
        } catch (ExecutionException exception) {
            RejectedExecutionException rejection = findExecutorRejection(exception);
            if (rejection != null) {
                throw readingPageOverloaded(rejection);
            }
            throw exception;
        }
    }

    private RejectedExecutionException findExecutorRejection(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof RejectedExecutionException rejection) {
                return rejection;
            }
            current = current.getCause();
        }
        return null;
    }

    private ResponseStatusException readingPageOverloaded(RejectedExecutionException exception) {
        meterRegistry.counter("novel.chapter.request.rejected").increment();
        log.warn("Reading page task rejected by executor");
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
            "Reading page temporarily unavailable", exception);
    }

    private record ChapterPageData(Book book, BookIndex bookIndex, Long preBookIndexId, Long nextBookIndexId,
                                   BookContent bookContent, boolean needBuy) {
    }

    private record ChapterPageResult(ChapterPageData data, ChapterPageFailure failure) {
        private static ChapterPageResult success(ChapterPageData data) {
            return new ChapterPageResult(data, null);
        }

        private static ChapterPageResult failure(ChapterPageFailure failure) {
            return new ChapterPageResult(null, failure);
        }
    }

    private enum ChapterPageFailure {
        BOOK_NOT_FOUND("Book not found"),
        CHAPTER_NOT_FOUND("Chapter not found in requested book"),
        INVALID_STORAGE_TYPE("Chapter storage type is unsupported"),
        CONTENT_NOT_FOUND("Chapter content is unavailable");

        private final String message;

        ChapterPageFailure(String message) {
            this.message = message;
        }

        private String message() {
            return message;
        }
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ModelAndView pageStatus(ResponseStatusException exception) {
        return new ModelAndView("404", exception.getStatusCode());
    }

    /**
     * 评论页面
     */
    @RequestMapping("/book/comment-{bookId}.html")
    public String commentList(@PathVariable("bookId") Long bookId, Model model) {
        //查询书籍
        Book book = bookService.findBook(bookId).orElse(null);
        if (book == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Book not found");
        }
        model.addAttribute("book", book);
        return "book/book_comment";
    }

    /**
     * 评论回复页面
     */
    @RequestMapping("/book/reply-{commentId}.html")
    public String commentReplyList(@PathVariable("commentId") Long commentId, Model model) {
        model.addAttribute("commentId", commentId);
        model.addAttribute("commentContent", bookService.getBookComment(commentId).getCommentContent());
        return "book/book_comment_reply";
    }

    /**
     * 新闻内容页面
     */
    @RequestMapping("/about/newsInfo-{newsId}.html")
    public String newsInfo(@PathVariable("newsId") Long newsId, Model model) {
        //查询新闻
        News news = newsService.queryNewsInfo(newsId);
        model.addAttribute("news", news);
        return "about/news_info";
    }


    /**
     * 作者注册页面
     */
    @RequestMapping("author/register.html")
    public String authorRegister(Author author, HttpServletRequest request, Model model) {
        UserDetails user = getUserDetails(request);
        if (user == null) {
            //未登录
            return "redirect:/user/login.html?originUrl=/author/register.html";
        }

        if (StringUtils.isNotBlank(author.getInviteCode())) {
            //提交作者注册信息
            String errorInfo = authorService.register(user.getId(), author);
            if (StringUtils.isBlank(errorInfo)) {
                //注册成功
                return "redirect:/author/index.html";
            }
            model.addAttribute("LabErr", errorInfo);
            model.addAttribute("author", author);
        }
        return "author/register";
    }


}
