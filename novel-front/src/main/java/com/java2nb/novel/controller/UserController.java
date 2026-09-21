package com.java2nb.novel.controller;


import com.java2nb.novel.core.bean.UserDetails;
import com.java2nb.novel.auth.AuthenticationService;
import com.java2nb.novel.auth.EmailCodeRequestOutcome;
import com.java2nb.novel.auth.dto.EmailCodeRequest;
import com.java2nb.novel.auth.dto.LoginRequest;
import com.java2nb.novel.auth.dto.RegisterRequest;
import com.java2nb.novel.auth.dto.PasswordResetRequest;
import com.java2nb.novel.auth.dto.PasswordChangeRequest;
import com.java2nb.novel.core.cache.CacheService;
import com.java2nb.novel.core.enums.ResponseStatus;
import com.java2nb.novel.entity.User;
import com.java2nb.novel.entity.UserBuyRecord;
import com.java2nb.novel.service.BookService;
import com.java2nb.novel.service.UserService;
import io.github.xxyopen.model.resp.RestResult;
import io.github.xxyopen.web.valid.UpdateGroup;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * @author 11797
 */
@RestController
@RequestMapping("user")
@RequiredArgsConstructor
@Slf4j
public class UserController extends BaseController {


    private final CacheService cacheService;

    private final UserService userService;

    private final AuthenticationService authenticationService;

    private final BookService bookService;

    /**
     * 登录
     */
    @PostMapping("login")
    public RestResult<Map<String, Object>> login(@Validated LoginRequest request) {

        //登录
        UserDetails userDetails = authenticationService.login(request.account(), request.password()).userDetails();

        Map<String, Object> data = new HashMap<>(1);
        data.put("token", jwtTokenUtil.generateToken(userDetails));

        return RestResult.ok(data);


    }

    @PostMapping("register/email-code")
    public RestResult<?> requestRegistrationCode(@Validated @ModelAttribute EmailCodeRequest codeRequest,
                                                  HttpServletRequest request) {
        EmailCodeRequestOutcome outcome = authenticationService.requestRegistrationCode(
            codeRequest.email(), request.getRemoteAddr());
        return switch (outcome) {
            case ACCEPTED -> RestResult.ok();
            case EMAIL_LIMITED, IP_LIMITED -> RestResult.fail(ResponseStatus.AUTH_CODE_LIMITED);
            case UNAVAILABLE -> RestResult.fail(ResponseStatus.AUTH_UNAVAILABLE);
        };
    }

    /** Email and code are verified by the authentication service before a user is inserted. */
    @PostMapping("register")
    public RestResult<?> register(@Validated @ModelAttribute RegisterRequest registerRequest) {
        UserDetails userDetails = authenticationService.register(registerRequest).userDetails();
        Map<String, Object> data = new HashMap<>(1);
        data.put("token", jwtTokenUtil.generateToken(userDetails));

        return RestResult.ok(data);


    }

    @PostMapping("password-reset/email-code")
    public RestResult<?> requestPasswordResetCode(@Validated @ModelAttribute EmailCodeRequest codeRequest,
                                                  HttpServletRequest request) {
        EmailCodeRequestOutcome outcome = authenticationService.requestPasswordResetCode(
            codeRequest.email(), request.getRemoteAddr());
        return switch (outcome) {
            case ACCEPTED -> RestResult.ok();
            case EMAIL_LIMITED, IP_LIMITED -> RestResult.fail(ResponseStatus.AUTH_CODE_LIMITED);
            case UNAVAILABLE -> RestResult.fail(ResponseStatus.AUTH_UNAVAILABLE);
        };
    }

    @PostMapping("password-reset")
    public RestResult<?> resetPassword(@Validated @ModelAttribute PasswordResetRequest resetRequest) {
        authenticationService.resetPassword(resetRequest);
        return RestResult.ok();
    }


    /**
     * 刷新token
     */
    @PostMapping("refreshToken")
    public RestResult<?> refreshToken(HttpServletRequest request) {
        UserDetails userDetail = getUserDetails(request);
        if (userDetail != null) {
            final String username;
            try { username = authenticationService.legacyUsername(userDetail.getId()); }
            catch (RuntimeException unavailable) { return RestResult.fail(ResponseStatus.AUTH_UNAVAILABLE); }
            String token = jwtTokenUtil.generateToken(userDetail);
            Map<String, Object> data = new HashMap<>(2);
            data.put("token", token);
            data.put("username", username);
            data.put("nickName", userDetail.getNickName());
            return RestResult.ok(data);

        } else {
            return RestResult.fail(ResponseStatus.NO_LOGIN);
        }

    }

    /**
     * 查询小说是否已加入书架
     */
    @GetMapping("queryIsInShelf")
    public RestResult<?> queryIsInShelf(Long bookId, HttpServletRequest request) {
        UserDetails userDetails = getUserDetails(request);
        if (userDetails == null) {
            return RestResult.fail(ResponseStatus.NO_LOGIN);
        }
        return RestResult.ok(userService.queryIsInShelf(userDetails.getId(), bookId));
    }

    /**
     * 加入书架
     */
    @PostMapping("addToBookShelf")
    public RestResult<Void> addToBookShelf(Long bookId, Long preContentId, HttpServletRequest request) {
        UserDetails userDetails = getUserDetails(request);
        if (userDetails == null) {
            return RestResult.fail(ResponseStatus.NO_LOGIN);
        }
        userService.addToBookShelf(userDetails.getId(), bookId, preContentId);
        return RestResult.ok();
    }

    /**
     * 移出书架
     */
    @DeleteMapping("removeFromBookShelf/{bookId}")
    public RestResult<?> removeFromBookShelf(@PathVariable("bookId") Long bookId, HttpServletRequest request) {
        UserDetails userDetails = getUserDetails(request);
        if (userDetails == null) {
            return RestResult.fail(ResponseStatus.NO_LOGIN);
        }
        userService.removeFromBookShelf(userDetails.getId(), bookId);
        return RestResult.ok();
    }

    /**
     * 分页查询书架
     */
    @GetMapping("listBookShelfByPage")
    public RestResult<?> listBookShelfByPage(@RequestParam(value = "curr", defaultValue = "1") int page,
        @RequestParam(value = "limit", defaultValue = "10") int pageSize, HttpServletRequest request) {
        UserDetails userDetails = getUserDetails(request);
        if (userDetails == null) {
            return RestResult.fail(ResponseStatus.NO_LOGIN);
        }
        return RestResult.ok(userService.listBookShelfByPage(userDetails.getId(), page, pageSize));
    }

    /**
     * 分页查询阅读记录
     */
    @GetMapping("listReadHistoryByPage")
    public RestResult<?> listReadHistoryByPage(@RequestParam(value = "curr", defaultValue = "1") int page,
        @RequestParam(value = "limit", defaultValue = "10") int pageSize, HttpServletRequest request) {
        UserDetails userDetails = getUserDetails(request);
        if (userDetails == null) {
            return RestResult.fail(ResponseStatus.NO_LOGIN);
        }
        return RestResult.ok(userService.listReadHistoryByPage(userDetails.getId(), page, pageSize));
    }

    /**
     * 添加阅读记录
     */
    @PostMapping("addReadHistory")
    public RestResult<?> addReadHistory(Long bookId, Long preContentId, HttpServletRequest request) {
        UserDetails userDetails = getUserDetails(request);
        if (userDetails == null) {
            return RestResult.fail(ResponseStatus.NO_LOGIN);
        }
        userService.addReadHistory(userDetails.getId(), bookId, preContentId);
        return RestResult.ok();
    }

    /**
     * 添加反馈
     */
    @PostMapping("addFeedBack")
    public RestResult<?> addFeedBack(String content, HttpServletRequest request) {
        UserDetails userDetails = getUserDetails(request);
        if (userDetails == null) {
            return RestResult.fail(ResponseStatus.NO_LOGIN);
        }
        userService.addFeedBack(userDetails.getId(), content);
        return RestResult.ok();
    }

    /**
     * 分页查询我的反馈列表
     */
    @GetMapping("listUserFeedBackByPage")
    public RestResult<?> listUserFeedBackByPage(@RequestParam(value = "curr", defaultValue = "1") int page,
        @RequestParam(value = "limit", defaultValue = "5") int pageSize, HttpServletRequest request) {
        UserDetails userDetails = getUserDetails(request);
        if (userDetails == null) {
            return RestResult.fail(ResponseStatus.NO_LOGIN);
        }
        return RestResult.ok(userService.listUserFeedBackByPage(userDetails.getId(), page, pageSize));
    }

    /**
     * 查询个人信息
     */
    @GetMapping("userInfo")
    public RestResult<?> userInfo(HttpServletRequest request) {
        UserDetails userDetails = getUserDetails(request);
        if (userDetails == null) {
            return RestResult.fail(ResponseStatus.NO_LOGIN);
        }
        return RestResult.ok(userService.userInfo(userDetails.getId()));
    }

    /**
     * 更新个人信息
     */
    @PostMapping("updateUserInfo")
    public RestResult<?> updateUserInfo(@Validated({UpdateGroup.class}) User user, HttpServletRequest request) {
        UserDetails userDetails = getUserDetails(request);
        if (userDetails == null) {
            return RestResult.fail(ResponseStatus.NO_LOGIN);
        }
        userService.updateUserInfo(userDetails.getId(), user);
        if (user.getNickName() != null) {
            userDetails.setNickName(user.getNickName());
            Map<String, Object> data = new HashMap<>(1);
            data.put("token", jwtTokenUtil.generateToken(userDetails));
            return RestResult.ok(data);
        }
        return RestResult.ok();
    }


    /**
     * 更新密码
     */
    @PostMapping("updatePassword")
    public RestResult<?> updatePassword(String oldPassword, String newPassword1, String newPassword2,
        HttpServletRequest request) {
        UserDetails userDetails = getUserDetails(request);
        if (userDetails == null) {
            return RestResult.fail(ResponseStatus.NO_LOGIN);
        }
        if (!StringUtils.equals(newPassword1, newPassword2)) {
            return RestResult.fail(ResponseStatus.TWO_PASSWORD_DIFF);
        }
        UserDetails updated = authenticationService.changePassword(userDetails.getId(),
            new PasswordChangeRequest(oldPassword, newPassword1, newPassword2)).userDetails();
        Map<String, Object> data = new HashMap<>(1);
        data.put("token", jwtTokenUtil.generateToken(updated));
        return RestResult.ok(data);
    }

    /**
     * 分页查询用户书评
     */
    @GetMapping("listCommentByPage")
    public RestResult<?> listCommentByPage(@RequestParam(value = "curr", defaultValue = "1") int page,
        @RequestParam(value = "limit", defaultValue = "5") int pageSize, HttpServletRequest request) {
        UserDetails userDetails = getUserDetails(request);
        if (userDetails == null) {
            return RestResult.fail(ResponseStatus.NO_LOGIN);
        }
        return RestResult.ok(bookService.listCommentByPage(userDetails.getId(), null, page, pageSize));
    }


    /**
     * 购买小说章节
     */
    @PostMapping("buyBookIndex")
    public RestResult<?> buyBookIndex(UserBuyRecord buyRecord, HttpServletRequest request) {
        UserDetails userDetails = getUserDetails(request);
        if (userDetails == null) {
            return RestResult.fail(ResponseStatus.NO_LOGIN);
        }
        buyRecord.setBuyAmount(bookService.queryBookIndex(buyRecord.getBookIndexId()).getBookPrice());
        userService.buyBookIndex(userDetails.getId(), buyRecord);
        return RestResult.ok();
    }


}
