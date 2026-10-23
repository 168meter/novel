package com.java2nb.novel.controller;
import com.java2nb.novel.service.*;
import com.java2nb.novel.messaging.BookVisitEventPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.Map;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
class ReactionControllerTest {
 private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder authenticated(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request){
  var user=new com.java2nb.novel.core.bean.UserDetails();user.setId(7L);return request.requestAttr("novel.auth.checked",true).requestAttr("novel.auth.user",user);
 }
 @Test void explicitRoutesUseServerIdentityAndRequireLiteralActive() throws Exception {
  var likes=mock(LikeService.class);when(likes.setCommentReaction(1L,7L,true,true)).thenReturn(new LikeService.ReactionState(1,0,true,false));
  var mvc=MockMvcBuilders.standaloneSetup(new BookController(mock(BookService.class),Map.of(),mock(IpLocationService.class),likes,mock(BookVisitEventPublisher.class))).setControllerAdvice(new com.java2nb.novel.core.advice.CommonExceptionHandler(),new com.java2nb.novel.core.advice.CustomResponseBodyAdvice(org.springframework.http.converter.json.Jackson2ObjectMapperBuilder.json())).setMessageConverters(new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter()).build();
  mvc.perform(authenticated(post("/book/setCommentLike").param("commentId","1").param("active","true").param("userId","99")))
   .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.data.likesCount").value("1")).andExpect(jsonPath("$.data.liked").value(true)).andExpect(jsonPath("$.data.disliked").value(false));
  verify(likes).setCommentReaction(1L,7L,true,true);clearInvocations(likes);
  mvc.perform(authenticated(post("/book/setCommentLike").param("commentId","1").param("active","yes"))).andExpect(jsonPath("$.code").value(400));
  mvc.perform(authenticated(post("/book/setCommentLike").param("commentId","1"))).andExpect(status().isBadRequest());verifyNoInteractions(likes);
 }
 @Test void allExplicitRoutesReturnTheSameStateShapeAndCorrectIntention() throws Exception {
  var likes=mock(LikeService.class);var state=new LikeService.ReactionState(2,3,false,true);
  when(likes.setCommentReaction(1L,7L,false,false)).thenReturn(state);when(likes.setReplyReaction(2L,7L,true,false)).thenReturn(state);when(likes.setReplyReaction(2L,7L,false,true)).thenReturn(state);
  var mvc=MockMvcBuilders.standaloneSetup(new BookController(mock(BookService.class),Map.of(),mock(IpLocationService.class),likes,mock(BookVisitEventPublisher.class))).setControllerAdvice(new com.java2nb.novel.core.advice.CommonExceptionHandler(),new com.java2nb.novel.core.advice.CustomResponseBodyAdvice(org.springframework.http.converter.json.Jackson2ObjectMapperBuilder.json())).setMessageConverters(new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter()).build();
  String[] routes={"setCommentUnLike","setReplyLike","setReplyUnLike"};String[] active={"false","false","true"};
  for(int i=0;i<routes.length;i++) mvc.perform(authenticated(post("/book/"+routes[i]).param("commentId","1").param("replyId","2").param("active",active[i]))).andExpect(jsonPath("$.data.unLikesCount").value("3")).andExpect(jsonPath("$.data.disliked").value(true));
  verify(likes).setCommentReaction(1L,7L,false,false);verify(likes).setReplyReaction(2L,7L,true,false);verify(likes).setReplyReaction(2L,7L,false,true);
 }
 @Test void readSnapshotsAreAuthenticatedAndNotCached() throws Exception {
  var likes=mock(LikeService.class);when(likes.getCommentReaction(1L,7L)).thenReturn(new LikeService.ReactionState(1,2,true,true));when(likes.getReplyReaction(2L,7L)).thenReturn(new LikeService.ReactionState(0,0,false,false));
  var mvc=MockMvcBuilders.standaloneSetup(new BookController(mock(BookService.class),Map.of(),mock(IpLocationService.class),likes,mock(BookVisitEventPublisher.class))).setControllerAdvice(new com.java2nb.novel.core.advice.CommonExceptionHandler(),new com.java2nb.novel.core.advice.CustomResponseBodyAdvice(org.springframework.http.converter.json.Jackson2ObjectMapperBuilder.json())).setMessageConverters(new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter()).build();
  mvc.perform(get("/book/commentReaction").param("commentId","1")).andExpect(jsonPath("$.code").value(1001));
  mvc.perform(authenticated(get("/book/commentReaction").param("commentId","1"))).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.data.unLikesCount").value("2"));
  mvc.perform(authenticated(get("/book/replyReaction").param("replyId","2"))).andExpect(jsonPath("$.data.liked").value(false));
 }
 @Test void legacyToggleKeepsExistingCountPayloadAndAdvertisesDeprecation() throws Exception {
  var likes=mock(LikeService.class);when(likes.toggleCommentLike(1L,7L)).thenReturn(4L);
  var mvc=MockMvcBuilders.standaloneSetup(new BookController(mock(BookService.class),Map.of(),mock(IpLocationService.class),likes,mock(BookVisitEventPublisher.class))).setControllerAdvice(new com.java2nb.novel.core.advice.CommonExceptionHandler(),new com.java2nb.novel.core.advice.CustomResponseBodyAdvice(org.springframework.http.converter.json.Jackson2ObjectMapperBuilder.json())).setMessageConverters(new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter()).build();
  mvc.perform(authenticated(post("/book/toggleCommentLike").param("commentId","1"))).andExpect(jsonPath("$.data").value("4")).andExpect(header().exists("Deprecation")).andExpect(header().string("Link","</book/setCommentLike>; rel=\"successor-version\""));
 }

 @Test void explicitMutationRoutesRejectAnonymousRequests() throws Exception {
  var likes=mock(LikeService.class);
  var mvc=MockMvcBuilders.standaloneSetup(new BookController(mock(BookService.class),Map.of(),mock(IpLocationService.class),likes,mock(BookVisitEventPublisher.class))).setControllerAdvice(new com.java2nb.novel.core.advice.CommonExceptionHandler(),new com.java2nb.novel.core.advice.CustomResponseBodyAdvice(org.springframework.http.converter.json.Jackson2ObjectMapperBuilder.json())).setMessageConverters(new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter()).build();
  for(String route:new String[]{"setCommentLike","setCommentUnLike","setReplyLike","setReplyUnLike"})
   mvc.perform(post("/book/"+route).param("commentId","1").param("replyId","2").param("active","true")).andExpect(status().isOk()).andExpect(jsonPath("$.code").value(1001));
  verifyNoInteractions(likes);
 }
}
