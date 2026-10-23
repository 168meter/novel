package com.java2nb.novel.service.impl;

import com.java2nb.novel.dao.BookCommentDao;
import com.java2nb.novel.dao.BookDao;
import com.java2nb.novel.dao.UserDao;
import com.java2nb.novel.domain.BookCommentDO;
import com.java2nb.novel.domain.BookDO;
import com.java2nb.novel.domain.UserDO;
import com.java2nb.novel.service.BookCommentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.transaction.annotation.Transactional;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Objects;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;


@Service
public class BookCommentServiceImpl implements BookCommentService {

    @Autowired
    private BookCommentDao bookCommentDao;
    @Autowired
    private com.java2nb.novel.comment.CommentModerationService moderation;
    @Autowired
    private BookDao bookDao;
    @Autowired
    private UserDao userDao;

    @Override
    public BookCommentDO get(Long id) {
        return bookCommentDao.get(id);
    }

    @Override
    public List<BookCommentDO> list(Map<String, Object> map) {
        List<BookCommentDO> list = bookCommentDao.list(map);
        if (!CollectionUtils.isEmpty(list)) {
            // Enrich valid rows without changing the original page or its ordering.
            List<BookCommentDO> comments = list.stream().filter(Objects::nonNull).collect(Collectors.toList());
            if (comments.isEmpty()) {
                return list;
            }
            List<Long> bookIds = comments.stream().map(BookCommentDO::getBookId).collect(Collectors.toList());
            Map<Long, String> bookNameMap = bookDao.batchGet(bookIds).stream()
                .collect(Collectors.toMap(BookDO::getId, BookDO::getBookName));
            List<Long> userIds = comments.stream().map(BookCommentDO::getCreateUserId)
                .filter(Objects::nonNull).collect(Collectors.toList());
            Map<Long, UserDO> userMap = new HashMap<>();
            if (!userIds.isEmpty()) {
                List<UserDO> users = userDao.batchGet(userIds);
                if (!CollectionUtils.isEmpty(users)) {
                    for (UserDO user : users) {
                        if (user != null && user.getId() != null) {
                            userMap.put(user.getId(), user);
                        }
                    }
                }
            }
            comments.forEach(v -> {
                v.setBookName(bookNameMap.get(v.getBookId()));
                v.setUserName(displayName(userMap.get(v.getCreateUserId())));
            });
        }
        return list;
    }

    private static String displayName(UserDO user) {
        if (user != null) {
            if (StringUtils.hasText(user.getNickName())) {
                return user.getNickName();
            }
            if (StringUtils.hasText(user.getUsername())) {
                return user.getUsername();
            }
        }
        return "未知用户";
    }

    @Override
    public int count(Map<String, Object> map) {
        return bookCommentDao.count(map);
    }

    @Override
    public int save(BookCommentDO row) {
        if(row==null) throw new com.java2nb.novel.comment.ModerationException(400);
        moderation.createComment(row.getCreateUserId(),row.getBookId(),row.getCommentContent(),null);return 1;
    }

    @Override
    public int update(BookCommentDO row) {
        if(row==null || row.getId()==null) return 0;
        // General edit cannot be used as a second moderation route.
        if(row.getAuditStatus()!=null) throw new com.java2nb.novel.comment.ModerationException(400);
        if(row.getCommentContent()==null) return 0;
        moderation.editComment(row.getId(),null,row.getModerationVersion(),row.getCommentContent());return 1;
    }

    @Override
    public int remove(Long id) { return moderation.removeComment(id); }

    @Override
    public int batchRemove(Long[] ids) { return moderation.batchRemove(ids); }
}
