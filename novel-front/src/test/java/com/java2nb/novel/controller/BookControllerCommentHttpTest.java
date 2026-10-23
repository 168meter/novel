package com.java2nb.novel.controller;

import com.java2nb.novel.messaging.BookVisitEventPublisher;
import com.java2nb.novel.service.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

class BookControllerCommentHttpTest {
    @Test void anonymousMutationRequestsCannotReachAnyService() throws Exception {
        var books=mock(BookService.class);var likes=mock(LikeService.class);
        var mvc=MockMvcBuilders.standaloneSetup(new BookController(books,Map.of(),mock(IpLocationService.class),likes,mock(BookVisitEventPublisher.class)))
            .setMessageConverters(new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter()).build();
        for(String route : new String[]{"addBookComment","addCommentReply","toggleCommentLike","toggleCommentUnLike","toggleReplyLike","toggleReplyUnLike","editComment","editReply"}) {
            mvc.perform(post("/book/"+route).param("bookId","1").param("commentId","10").param("replyId","20").param("createUserId","7").param("id","10").param("version","0").param("content","edited"))
                .andExpect(jsonPath("$.code").value(1001));
        }
        verifyNoInteractions(books,likes);
    }
}
