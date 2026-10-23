package com.java2nb.novel.service.impl;

import com.java2nb.common.exception.CommonExceptionHandler;
import com.java2nb.novel.cache.AdminChapterCacheInvalidator;
import com.java2nb.novel.controller.*;
import com.java2nb.novel.dao.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Production binding/service/advice; standalone MVC does not prove deployed Shiro authorization. */
class ChapterIdentityHttpTest {
    MockMvc mvc;
    BookIndexDao indexes;
    BookContentDao contents;
    @BeforeEach void setup() {
        indexes=mock(BookIndexDao.class);contents=mock(BookContentDao.class);
        var invalidator=new AdminChapterCacheInvalidator(mock(StringRedisTemplate.class),new SimpleMeterRegistry());
        var directories=new BookIndexController();var bodies=new BookContentController();
        ReflectionTestUtils.setField(directories,"bookIndexService",new BookIndexServiceImpl(indexes,invalidator,mock(BookDao.class)));
        ReflectionTestUtils.setField(bodies,"bookContentService",new BookContentServiceImpl(contents,indexes,invalidator));
        mvc=MockMvcBuilders.standaloneSetup(directories,bodies)
            .defaultRequest(get("/").accept("application/json"))
            .setControllerAdvice(new CommonExceptionHandler()).build();
        when(indexes.get(201L)).thenReturn(ChapterIdentityContractTest.index());
        when(contents.get(301L)).thenReturn(ChapterIdentityContractTest.body(201L));
    }
    @Test void forgedBookChangeReturnsExplicitBusinessError() throws Exception {
        mvc.perform(post("/novel/bookIndex/update").param("id","201").param("bookId","102").param("indexName","new"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(400)).andExpect(jsonPath("$.msg").value(org.hamcrest.Matchers.containsString("book_id")));
        verify(indexes,never()).update(any());
    }
    @Test void forgedStorageChangeReturnsExplicitBusinessError() throws Exception {
        mvc.perform(post("/novel/bookIndex/update").param("id","201").param("storageType","txt"))
            .andExpect(jsonPath("$.code").value(400)).andExpect(jsonPath("$.msg").value(org.hamcrest.Matchers.containsString("storage_type")));
        verify(indexes,never()).update(any());
    }
    @Test void forgedBodyDirectoryChangeReturnsExplicitBusinessError() throws Exception {
        mvc.perform(post("/novel/bookContent/update").param("id","301").param("indexId","202").param("content","new"))
            .andExpect(jsonPath("$.code").value(400)).andExpect(jsonPath("$.msg").value(org.hamcrest.Matchers.containsString("index_id")));
        verify(contents,never()).update(any());
    }
    @Test void unchangedIdentityFromReadonlyFormAllowsEdit() throws Exception {
        when(indexes.update(any())).thenReturn(1);
        mvc.perform(post("/novel/bookIndex/update").param("id","201").param("bookId","101").param("storageType","db").param("indexName","new"))
            .andExpect(jsonPath("$.code").value(0));
        verify(indexes).update(org.mockito.ArgumentMatchers.argThat(p->"new".equals(p.getIndexName())));
    }
    @Test void bodyEditWithoutIdentityIsAllowed() throws Exception {
        when(contents.update(any())).thenReturn(1);
        mvc.perform(post("/novel/bookContent/update").param("id","301").param("content","new"))
            .andExpect(jsonPath("$.code").value(0));
        verify(contents).update(org.mockito.ArgumentMatchers.argThat(p->"new".equals(p.getContent())));
    }
}

