package com.java2nb.novel.controller;

import com.java2nb.novel.core.advice.CommonExceptionHandler;
import com.java2nb.novel.entity.Book;
import com.java2nb.novel.messaging.BookVisitEventPublisher;
import com.java2nb.novel.service.BookService;
import com.java2nb.novel.service.IpLocationService;
import com.java2nb.novel.service.LikeService;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class BookControllerNotFoundTest {
    private BookService books;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        books = mock(BookService.class);
        mvc = MockMvcBuilders.standaloneSetup(new BookController(books, Map.of(),
                mock(IpLocationService.class), mock(LikeService.class), mock(BookVisitEventPublisher.class)))
            .setControllerAdvice(new CommonExceptionHandler(), new com.java2nb.novel.core.advice.CustomResponseBodyAdvice(
                new org.springframework.http.converter.json.Jackson2ObjectMapperBuilder()))
            .setMessageConverters(new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter())
            .build();
    }

    @Test
    void existingBookKeepsSuccessfulJsonBody() throws Exception {
        Book book = new Book();
        book.setId(42L);
        book.setBookName("Existing book");
        when(books.findBook(42L)).thenReturn(Optional.of(book));
        when(books.queryBookDetail(42L)).thenReturn(book);
        mvc.perform(get("/book/queryBookDetail/42"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.bookName").value("Existing book"));
    }

    @Test
    void absentBookReturnsJson404WithoutInternalDetails() throws Exception {
        when(books.findBook(999L)).thenReturn(Optional.empty());
        when(books.queryBookDetail(999L)).thenThrow(new NoSuchElementException("Book not found"));
        mvc.perform(get("/book/queryBookDetail/999"))
            .andExpect(status().isNotFound())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.code").value(404))
            .andExpect(jsonPath("$.msg").value("请求的内容不存在"));
    }

    @Test
    void databaseFailureStillReturns500() throws Exception {
        when(books.findBook(42L)).thenThrow(new IllegalStateException("private database detail"));
        when(books.queryBookDetail(42L)).thenThrow(new IllegalStateException("private database detail"));
        mvc.perform(get("/book/queryBookDetail/42"))
            .andExpect(status().isInternalServerError())
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private database detail"))));
    }
}
