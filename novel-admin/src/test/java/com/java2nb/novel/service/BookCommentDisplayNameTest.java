package com.java2nb.novel.service;

import com.java2nb.novel.dao.BookCommentDao;
import com.java2nb.novel.dao.BookDao;
import com.java2nb.novel.dao.UserDao;
import com.java2nb.novel.domain.BookCommentDO;
import com.java2nb.novel.domain.BookDO;
import com.java2nb.novel.domain.UserDO;
import com.java2nb.novel.service.impl.BookCommentServiceImpl;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BookCommentDisplayNameTest {
    private final BookCommentDao comments = mock(BookCommentDao.class);
    private final BookDao books = mock(BookDao.class);
    private final UserDao users = mock(UserDao.class);
    private final BookCommentServiceImpl service = new BookCommentServiceImpl();
    private final Map<String, Object> query = Map.of("offset", 10, "limit", 5, "auditStatus", 1);

    @BeforeEach
    void wireService() {
        ReflectionTestUtils.setField(service, "bookCommentDao", comments);
        ReflectionTestUtils.setField(service, "bookDao", books);
        ReflectionTestUtils.setField(service, "userDao", users);
    }

    private BookCommentDO comment(Long author) {
        var row = new BookCommentDO();
        row.setId(13L);
        row.setBookId(3L);
        row.setCreateUserId(author);
        row.setAuditStatus(1);
        row.setReplyCount(2);
        row.setModerationVersion(4L);
        return row;
    }

    private UserDO user(Long id, String nickname, String username) {
        var row = new UserDO();
        row.setId(id);
        row.setNickName(nickname);
        row.setUsername(username);
        return row;
    }

    private void rows(List<BookCommentDO> rows) {
        when(comments.list(query)).thenReturn(rows);
        var book = new BookDO();
        book.setId(3L);
        book.setBookName("测试书籍");
        when(books.batchGet(anyList())).thenReturn(List.of(book));
    }

    static Stream<Arguments> names() {
        return Stream.of(
            Arguments.of(null, "legacy", "legacy"),
            Arguments.of("", "legacy", "legacy"),
            Arguments.of(" \t\n", "legacy", "legacy"),
            Arguments.of("昵称", "legacy", "昵称"),
            Arguments.of("读者42", null, "读者42"),
            Arguments.of("昵称", "", "昵称"),
            Arguments.of(null, null, "未知用户"),
            Arguments.of(null, " \t\n", "未知用户"),
            Arguments.of("\u2003", "\u2003", "未知用户"),
            Arguments.of("  昵称  ", "legacy", "  昵称  ")
        );
    }

    @ParameterizedTest
    @MethodSource("names")
    void resolvesDisplayNameWithoutRequiringUsername(String nickname, String username, String expected) {
        var row = comment(42L);
        rows(List.of(row));
        when(users.batchGet(anyList())).thenReturn(List.of(user(42L, nickname, username)));
        assertThat(service.list(query)).containsExactly(row);
        assertThat(row.getUserName()).isEqualTo(expected);
        assertThat(row.getBookName()).isEqualTo("测试书籍");
    }

    @Test
    void missingRelatedUserDisplaysUnknown() {
        var row = comment(42L);
        rows(List.of(row));
        when(users.batchGet(anyList())).thenReturn(List.of());
        service.list(query);
        assertThat(row.getUserName()).isEqualTo("未知用户");
    }

    @Test
    void nullUserBatchDisplaysUnknown() {
        var row = comment(42L);
        rows(List.of(row));
        when(users.batchGet(anyList())).thenReturn(null);
        service.list(query);
        assertThat(row.getUserName()).isEqualTo("未知用户");
    }

    @Test
    void nullUserRowsAndMissingIdsDoNotHideValidUser() {
        var row = comment(42L);
        rows(List.of(row));
        when(users.batchGet(anyList())).thenReturn(Arrays.asList(null, user(null, "无关联", null), user(42L, "读者42", null)));
        service.list(query);
        assertThat(row.getUserName()).isEqualTo("读者42");
    }

    @Test
    void nullCreatorDoesNotSendAnEmptyUserQuery() {
        var row = comment(null);
        rows(List.of(row));
        service.list(query);
        assertThat(row.getUserName()).isEqualTo("未知用户");
        verifyNoInteractions(users);
    }

    @Test
    void emptyCommentsKeepOriginalListAndSkipEnrichment() {
        List<BookCommentDO> empty = new ArrayList<>();
        when(comments.list(query)).thenReturn(empty);
        assertThat(service.list(query)).isSameAs(empty);
        verifyNoInteractions(books, users);
    }

    @Test
    void nullCommentResultKeepsExistingReturnContractAndSkipsEnrichment() {
        when(comments.list(query)).thenReturn(null);
        assertThat(service.list(query)).isNull();
        verifyNoInteractions(books, users);
    }

    @Test
    void nullCommentEntryDoesNotChangePageShapeOrPreventValidEnrichment() {
        var row = comment(42L);
        var page = Arrays.asList(null, row);
        rows(page);
        when(users.batchGet(anyList())).thenReturn(List.of(user(42L, "读者42", null)));
        assertThat(service.list(query)).isSameAs(page).containsExactly(null, row);
        assertThat(row.getUserName()).isEqualTo("读者42");
    }

    @Test
    void allNullCommentEntriesSkipEnrichmentAndKeepPageShape() {
        List<BookCommentDO> page = Collections.singletonList(null);
        when(comments.list(query)).thenReturn(page);
        assertThat(service.list(query)).isSameAs(page);
        verifyNoInteractions(books, users);
    }

    @Test
    void enrichmentKeepsPagingArgumentsOrderAndModerationFields() {
        var first = comment(42L);
        var second = comment(43L);
        second.setId(12L);
        var page = new ArrayList<>(List.of(first, second));
        rows(page);
        when(users.batchGet(anyList())).thenReturn(List.of(user(43L, "第二位", null), user(42L, "第一位", null)));
        assertThat(service.list(query)).isSameAs(page).containsExactly(first, second);
        assertThat(first.getUserName()).isEqualTo("第一位");
        assertThat(second.getUserName()).isEqualTo("第二位");
        assertThat(first.getAuditStatus()).isEqualTo(1);
        assertThat(first.getReplyCount()).isEqualTo(2);
        assertThat(first.getModerationVersion()).isEqualTo(4L);
        verify(comments).list(same(query));
    }

    @Test
    void countKeepsExistingDaoContract() {
        when(comments.count(query)).thenReturn(17);
        assertThat(service.count(query)).isEqualTo(17);
        verify(comments).count(same(query));
        verifyNoInteractions(books, users);
    }
}
