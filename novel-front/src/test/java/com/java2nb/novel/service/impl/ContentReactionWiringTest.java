package com.java2nb.novel.service.impl;

import com.java2nb.novel.mapper.*;
import com.java2nb.novel.service.cache.ChapterContentCache;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Mirror MapperScan's simultaneous base/derived beans; no database or Redis needed. */
class ContentReactionWiringTest {
    @Test
    void reactionsStartWithBothBaseAndFrontMappersAndUseTheIntendedTargets() {
        var comments = mock(BookCommentMapper.class);
        var frontComments = mock(FrontBookCommentMapper.class);
        var replies = mock(BookCommentReplyMapper.class);
        var frontReplies = mock(FrontBookCommentReplyMapper.class);
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean("bookCommentMapper", BookCommentMapper.class, () -> comments);
            context.registerBean("frontBookCommentMapper", FrontBookCommentMapper.class, () -> frontComments);
            context.registerBean("bookCommentReplyMapper", BookCommentReplyMapper.class, () -> replies);
            context.registerBean("frontBookCommentReplyMapper", FrontBookCommentReplyMapper.class, () -> frontReplies);
            context.registerBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class));
            context.register(LikeServiceImpl.class);
            context.refresh();
            // Ignore Spring's Aware/InitializingBean lifecycle calls on the mock.
            clearInvocations(context.getBean(StringRedisTemplate.class));
            var service = context.getBean(LikeServiceImpl.class);
            assertThatThrownBy(() -> service.toggleCommentLike(1L, 7L))
                .isInstanceOf(io.github.xxyopen.web.exception.BusinessException.class);
            assertThatThrownBy(() -> service.toggleReplyLike(2L, 7L))
                .isInstanceOf(io.github.xxyopen.web.exception.BusinessException.class);
            verify(comments).selectByPrimaryKey(1L);
            verify(replies).selectByPrimaryKey(2L);
            verifyNoInteractions(frontComments, frontReplies, context.getBean(StringRedisTemplate.class));
        }
    }

    @Test
    void bothContentEntrypointsResolveBaseIndexMapperDespiteFrontSubtype() {
        var indexes = mock(BookIndexMapper.class);
        var frontIndexes = mock(FrontBookIndexMapper.class);
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(
                new org.springframework.core.env.MapPropertySource("test", java.util.Map.of("content.save.path", ".")));
            context.registerBean("bookIndexMapper", BookIndexMapper.class, () -> indexes);
            context.registerBean("frontBookIndexMapper", FrontBookIndexMapper.class, () -> frontIndexes);
            context.registerBean(BookContentMapper.class, () -> mock(BookContentMapper.class));
            context.registerBean(ChapterContentCache.class, () -> mock(ChapterContentCache.class));
            context.register(DbBookContentServiceImpl.class, FileBookContentServiceImpl.class);
            context.refresh();
            assertThat(context.getBean(DbBookContentServiceImpl.class).queryBookContent(9L, 1L)).isNull();
            assertThat(context.getBean(FileBookContentServiceImpl.class).queryBookContent(9L, 2L)).isNull();
            verify(indexes).selectByPrimaryKey(1L);
            verify(indexes).selectByPrimaryKey(2L);
            verifyNoInteractions(frontIndexes, context.getBean(BookContentMapper.class), context.getBean(ChapterContentCache.class));
        }
    }
}
