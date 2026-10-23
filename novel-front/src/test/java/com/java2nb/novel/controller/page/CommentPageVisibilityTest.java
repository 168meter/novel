package com.java2nb.novel.controller.page;
import com.java2nb.novel.service.*;
import com.java2nb.novel.engagement.ReadingPageVisitRegistrar;
import com.java2nb.novel.entity.BookComment;
import com.java2nb.novel.core.utils.ThreadLocalUtil;
import org.junit.jupiter.api.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.View;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.*;
import java.util.concurrent.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
class CommentPageVisibilityTest {
 @Test void unavailableParentDirectPageIs404AndDoesNotDereferenceNull() throws Exception {
  var books=mock(BookService.class);var metrics=new SimpleMeterRegistry();
  try(var pool=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new LinkedBlockingQueue<>())) {
   var controller=new PageController(books,mock(NewsService.class),mock(AuthorService.class),mock(UserService.class),pool,Map.of(),mock(ReadingPageVisitRegistrar.class),metrics);
   var mvc=MockMvcBuilders.standaloneSetup(controller).setViewResolvers((name,locale)->(View)(model,request,response)->response.getWriter().write(name)).build();
   mvc.perform(get("/book/reply-10.html")).andExpect(status().isNotFound());
   var row=new BookComment();row.setId(10L);row.setCommentContent("approved");when(books.getBookComment(10L)).thenReturn(row);
   mvc.perform(get("/book/reply-10.html")).andExpect(status().isOk()).andExpect(model().attribute("commentContent","approved"));
  } finally {metrics.close();ThreadLocalUtil.clear();}
 }
}
