package com.java2nb.novel.controller;

import com.java2nb.novel.auth.AuthenticationService;
import com.java2nb.novel.auth.model.AuthenticationResult;
import com.java2nb.novel.core.bean.UserDetails;
import com.java2nb.novel.core.cache.CacheService;
import com.java2nb.novel.core.utils.JwtTokenUtil;
import com.java2nb.novel.service.BookService;
import com.java2nb.novel.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

class UserControllerAuthenticationTest {
    @Test void existingPhoneFormKeepsTokenResponse() throws Exception {
        AuthenticationService auth = mock(AuthenticationService.class);
        UserDetails details = new UserDetails();
        details.setId(7L);
        when(auth.login("13800138000", "123")).thenReturn(new AuthenticationResult(details));
        JwtTokenUtil tokens = mock(JwtTokenUtil.class);
        when(tokens.generateToken(details)).thenReturn("signed-token");
        UserController controller = new UserController(mock(CacheService.class), mock(UserService.class),
            auth, mock(BookService.class));
        controller.setJwtTokenUtil(tokens);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        String body = mvc.perform(post("/user/login")
                .param("username", "13800138000").param("password", "123"))
            .andReturn().getResponse().getContentAsString();
        assertThat(body).contains("signed-token");
    }
}
