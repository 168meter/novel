package com.java2nb.novel.controller;

import com.java2nb.novel.core.bean.UserDetails;
import com.java2nb.novel.core.enums.ResponseStatus;
import com.java2nb.novel.core.utils.IpUtil;
import com.java2nb.novel.entity.*;
import com.java2nb.novel.messaging.BookVisitEventPublisher;
import com.java2nb.novel.service.BookContentService;
import com.java2nb.novel.service.BookService;
import com.java2nb.novel.service.IpLocationService;
import com.java2nb.novel.service.LikeService;
import com.java2nb.novel.vo.*;
import io.github.xxyopen.model.page.PageBean;
import io.github.xxyopen.model.page.builder.pagehelper.PageBuilder;
import io.github.xxyopen.model.resp.RestResult;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author 11797
 */
@RequestMapping("book")
@RestController
@Slf4j
@RequiredArgsConstructor
public class BookController extends BaseController {

    private final BookService bookService;

    private final Map<String, BookContentService> bookContentServiceMap;

    private final IpLocationService ipLocationService;

    private final LikeService likeService;

    private final BookVisitEventPublisher bookVisitEventPublisher;

    /**
     * 查询首页小说设置列表数据
     */
    @GetMapping("listBookSetting")
    public RestResult<Map<String, List<BookSettingVO>>> listBookSetting() {
        return RestResult.ok(bookService.listBookSettingVO());
    }

    /**
     * 查询首页点击榜单数据
     */
    @GetMapping("listClickRank")
    public RestResult<List<Book>> listClickRank() {
        return RestResult.ok(bookService.listClickRank());
    }

    /**
     * 查询首页新书榜单数据
     */
    @GetMapping("listNewRank")
    public RestResult<List<Book>> listNewRank() {
        return RestResult.ok(bookService.listNewRank());
    }

    /**
     * 查询首页更新榜单数据
     */
    @GetMapping("listUpdateRank")
    public RestResult<List<BookVO>> listUpdateRank() {
        return RestResult.ok(bookService.listUpdateRank());
    }

    /**
     * 查询小说分类列表
     */
    @GetMapping("listBookCategory")
    public RestResult<List<BookCategory>> listBookCategory() {
        return RestResult.ok(bookService.listBookCategory());
    }

    /**
     * 分页搜索
     */
    @GetMapping("searchByPage")
    public RestResult<?> searchByPage(@Validated BookSpVO bookSP, @RequestParam(value = "curr", defaultValue = "1") int page,
        @RequestParam(value = "limit", defaultValue = "20") int pageSize) {
        return RestResult.ok(bookService.searchByPage(bookSP, page, pageSize));
    }

    /**
     * 查询小说详情信息
     */
    @GetMapping("queryBookDetail/{id}")
    public ResponseEntity<RestResult<Book>> queryBookDetail(@PathVariable("id") Long id) {
        return bookService.findBook(id)
            .map(book -> ResponseEntity.ok(RestResult.ok(book)))
            .orElseGet(() -> {
                RestResult<Book> result = RestResult.ok((Book) null);
                result.setCode(404);
                result.setMsg("请求的内容不存在");
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(result);
            });
    }


    /**
     * 查询小说排行信息
     */
    @GetMapping("listRank")
    public RestResult<List<Book>> listRank(@RequestParam(value = "type", defaultValue = "0") Byte type,
        @RequestParam(value = "limit", defaultValue = "30") Integer limit) {
        return RestResult.ok(bookService.listRank(type, limit));
    }

    /**
     * 增加点击次数
     */
    @PostMapping("addVisitCount")
    public ResponseEntity<RestResult<Void>> addVisitCount(Long bookId) {
        if (bookId == null || bookId <= 0) {
            return ResponseEntity.badRequest().body(RestResult.fail(io.github.xxyopen.model.resp.SysResultCode.PARAM_ERROR));
        }
        if (!bookVisitEventPublisher.publish(bookId)) {
            RestResult<Void> failure = RestResult.error();
            failure.setMsg("访问统计暂不可用，发送未确认");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(failure);
        }
        RestResult<Void> accepted = RestResult.ok();
        accepted.setMsg("已提交异步发送，尚未确认统计完成");
        return ResponseEntity.accepted().body(accepted);
    }

    /**
     * 查询章节相关信息
     */
    @GetMapping("queryBookIndexAbout")
    public RestResult<?> queryBookIndexAbout(Long bookId, Long lastBookIndexId) {
        BookService.OwnedChapter owned = bookService.findOwnedChapter(bookId, lastBookIndexId).orElse(null);
        if (owned == null) {
            return RestResult.fail(io.github.xxyopen.model.resp.SysResultCode.PARAM_ERROR);
        }
        BookIndex bookIndex = owned.bookIndex();
        BookContentService contents = bookContentServiceMap.get(bookIndex.getStorageType());
        if (contents == null) {
            return RestResult.fail(io.github.xxyopen.model.resp.SysResultCode.PARAM_ERROR);
        }
        BookContent content = contents.queryBookContent(owned);
        if (content == null || content.getContent() == null) {
            return RestResult.fail(io.github.xxyopen.model.resp.SysResultCode.PARAM_ERROR);
        }
        Map<String, Object> data = new HashMap<>(2);
        data.put("bookIndexCount", bookService.queryIndexCount(bookId));
        String lastBookContent = content.getContent();
        if (lastBookContent.length() > 42) {
            lastBookContent = lastBookContent.substring(0, 42);
        }
        data.put("lastBookContent", lastBookContent);
        return RestResult.ok(data);
    }

    /**
     * 根据分类id查询同类推荐书籍
     */
    @GetMapping("listRecBookByCatId")
    public RestResult<List<Book>> listRecBookByCatId(Integer catId) {
        return RestResult.ok(bookService.listRecBookByCatId(catId));
    }


    /**
     * 分页查询书籍评论列表
     */
    @GetMapping("listCommentByPage")
    public RestResult<PageBean<BookCommentVO>> listCommentByPage(@RequestParam("bookId") Long bookId,
        @RequestParam(value = "curr", defaultValue = "1") int page,
        @RequestParam(value = "limit", defaultValue = "5") int pageSize) {
        return RestResult.ok(bookService.listCommentByPage(null, bookId, page, pageSize));
    }

    /**
     * 分页查询评论回复列表
     */
    @GetMapping("listCommentReplyByPage")
    public RestResult<PageBean<BookCommentReplyVO>> listCommentReplyByPage(@RequestParam("commentId") Long commentId,
        @RequestParam(value = "curr", defaultValue = "1") int page,
        @RequestParam(value = "limit", defaultValue = "5") int pageSize) {
        return RestResult.ok(bookService.listCommentReplyByPage(null, commentId, page, pageSize));
    }

    /**
     * 新增评价
     */
    @PostMapping("addBookComment")
    public RestResult<?> addBookComment(BookComment comment, HttpServletRequest request) {
        UserDetails userDetails = getUserDetails(request);
        if (userDetails == null) {
            return RestResult.fail(ResponseStatus.NO_LOGIN);
        }
        comment.setLocation(ipLocationService.getLocation(IpUtil.getRealIp(request)));
        bookService.addBookComment(userDetails.getId(), comment);
        return RestResult.ok();
    }

    @PostMapping("editComment")
    public RestResult<?> editComment(@RequestParam Long id,@RequestParam Long version,@RequestParam String content,HttpServletRequest request) {
        UserDetails user=getUserDetails(request);
        if(user==null) return RestResult.fail(ResponseStatus.NO_LOGIN);
        bookService.editComment(user.getId(),id,version,content);return RestResult.ok();
    }

    @PostMapping("editReply")
    public RestResult<?> editReply(@RequestParam Long id,@RequestParam Long version,@RequestParam String content,HttpServletRequest request) {
        UserDetails user=getUserDetails(request);
        if(user==null) return RestResult.fail(ResponseStatus.NO_LOGIN);
        bookService.editReply(user.getId(),id,version,content);return RestResult.ok();
    }

    @PostMapping("setCommentLike")
    public ResponseEntity<RestResult<?>> setCommentLike(@RequestParam Long commentId,@RequestParam String active,HttpServletRequest request) {
        var user=getUserDetails(request);if(user==null)return reactionResponse(RestResult.fail(ResponseStatus.NO_LOGIN));
        return reactionResponse(RestResult.ok(likeService.setCommentReaction(commentId,user.getId(),true,reactionActive(active))));
    }
    @PostMapping("setCommentUnLike")
    public ResponseEntity<RestResult<?>> setCommentUnLike(@RequestParam Long commentId,@RequestParam String active,HttpServletRequest request) {
        var user=getUserDetails(request);if(user==null)return reactionResponse(RestResult.fail(ResponseStatus.NO_LOGIN));
        return reactionResponse(RestResult.ok(likeService.setCommentReaction(commentId,user.getId(),false,reactionActive(active))));
    }
    @PostMapping("setReplyLike")
    public ResponseEntity<RestResult<?>> setReplyLike(@RequestParam Long replyId,@RequestParam String active,HttpServletRequest request) {
        var user=getUserDetails(request);if(user==null)return reactionResponse(RestResult.fail(ResponseStatus.NO_LOGIN));
        return reactionResponse(RestResult.ok(likeService.setReplyReaction(replyId,user.getId(),true,reactionActive(active))));
    }
    @PostMapping("setReplyUnLike")
    public ResponseEntity<RestResult<?>> setReplyUnLike(@RequestParam Long replyId,@RequestParam String active,HttpServletRequest request) {
        var user=getUserDetails(request);if(user==null)return reactionResponse(RestResult.fail(ResponseStatus.NO_LOGIN));
        return reactionResponse(RestResult.ok(likeService.setReplyReaction(replyId,user.getId(),false,reactionActive(active))));
    }
    @GetMapping("commentReaction")
    public ResponseEntity<RestResult<?>> commentReaction(@RequestParam Long commentId,HttpServletRequest request) {
        var user=getUserDetails(request);if(user==null)return reactionResponse(RestResult.fail(ResponseStatus.NO_LOGIN));
        return reactionResponse(RestResult.ok(likeService.getCommentReaction(commentId,user.getId())));
    }
    @GetMapping("replyReaction")
    public ResponseEntity<RestResult<?>> replyReaction(@RequestParam Long replyId,HttpServletRequest request) {
        var user=getUserDetails(request);if(user==null)return reactionResponse(RestResult.fail(ResponseStatus.NO_LOGIN));
        return reactionResponse(RestResult.ok(likeService.getReplyReaction(replyId,user.getId())));
    }
    private static boolean reactionActive(String active) {
        if(!"true".equals(active) && !"false".equals(active)) throw new io.github.xxyopen.web.exception.BusinessException(io.github.xxyopen.model.resp.SysResultCode.PARAM_ERROR);
        return "true".equals(active);
    }
    private static ResponseEntity<RestResult<?>> reactionResponse(RestResult<?> body) {
        return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore()).body(body);
    }
    private static ResponseEntity<RestResult<?>> deprecatedReaction(RestResult<?> body,String successor) {
        return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore()).header("Deprecation","@1792368000")
            .header("Link","</book/"+successor+">; rel=\"successor-version\"").body(body);
    }

    /**
     * 评价点赞/取消点赞
     */
    @Deprecated(since="2026-10-19",forRemoval=false)
    @PostMapping("toggleCommentLike")
    public ResponseEntity<RestResult<?>> toggleCommentLike(Long commentId, HttpServletRequest request) {
        UserDetails userDetails = getUserDetails(request);
        if (userDetails == null) {
            return deprecatedReaction(RestResult.fail(ResponseStatus.NO_LOGIN),"setCommentLike");
        }
        return deprecatedReaction(RestResult.ok(likeService.toggleCommentLike(commentId, userDetails.getId())),"setCommentLike");
    }

    /**
     * 评价点踩/取消点踩
     */
    @Deprecated(since="2026-10-19",forRemoval=false)
    @PostMapping("toggleCommentUnLike")
    public ResponseEntity<RestResult<?>> toggleCommentUnLike(Long commentId, HttpServletRequest request) {
        UserDetails userDetails = getUserDetails(request);
        if (userDetails == null) {
            return deprecatedReaction(RestResult.fail(ResponseStatus.NO_LOGIN),"setCommentUnLike");
        }
        return deprecatedReaction(RestResult.ok(likeService.toggleCommentUnLike(commentId, userDetails.getId())),"setCommentUnLike");
    }

    /**
     * 新增回复
     */
    @PostMapping("addCommentReply")
    public RestResult<?> addCommentReply(BookCommentReply commentReply, HttpServletRequest request) {
        UserDetails userDetails = getUserDetails(request);
        if (userDetails == null) {
            return RestResult.fail(ResponseStatus.NO_LOGIN);
        }
        commentReply.setLocation(ipLocationService.getLocation(IpUtil.getRealIp(request)));
        bookService.addBookCommentReply(userDetails.getId(), commentReply);
        return RestResult.ok();
    }

    /**
     * 回复点赞/取消点赞
     */
    @Deprecated(since="2026-10-19",forRemoval=false)
    @PostMapping("toggleReplyLike")
    public ResponseEntity<RestResult<?>> toggleReplyLike(Long replyId, HttpServletRequest request) {
        UserDetails userDetails = getUserDetails(request);
        if (userDetails == null) {
            return deprecatedReaction(RestResult.fail(ResponseStatus.NO_LOGIN),"setReplyLike");
        }
        return deprecatedReaction(RestResult.ok(likeService.toggleReplyLike(replyId, userDetails.getId())),"setReplyLike");
    }

    /**
     * 回复点赞/取消点赞
     */
    @Deprecated(since="2026-10-19",forRemoval=false)
    @PostMapping("toggleReplyUnLike")
    public ResponseEntity<RestResult<?>> toggleReplyUnLike(Long replyId, HttpServletRequest request) {
        UserDetails userDetails = getUserDetails(request);
        if (userDetails == null) {
            return deprecatedReaction(RestResult.fail(ResponseStatus.NO_LOGIN),"setReplyUnLike");
        }
        return deprecatedReaction(RestResult.ok(likeService.toggleReplyUnLike(replyId, userDetails.getId())),"setReplyUnLike");
    }

    /**
     * 根据小说ID查询小说前十条最新更新目录集合
     */
    @GetMapping("queryNewIndexList")
    public RestResult<List<BookIndex>> queryNewIndexList(Long bookId) {
        return RestResult.ok(bookService.queryIndexList(bookId, "index_num desc", 1, 10));
    }

    /**
     * 目录页
     */
    @GetMapping("/queryIndexList")
    public RestResult<PageBean<BookIndex>> indexList(Long bookId,
        @RequestParam(value = "curr", defaultValue = "1") int page,
        @RequestParam(value = "limit", defaultValue = "5") int pageSize,
        @RequestParam(value = "orderBy", defaultValue = "index_num desc") String orderBy) {
        return RestResult.ok(PageBuilder.build(bookService.queryIndexList(bookId, orderBy, page, pageSize)));
    }


}
