package com.java2nb.novel.controller;

import com.java2nb.novel.core.enums.ResponseStatus;
import com.java2nb.novel.service.AuthorService;
import com.java2nb.novel.service.BookService;
import io.github.xxyopen.web.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class AuthorControllerOptionalAiTest {

    @Test
    void rejectsAiRequestCleanlyWhenChatAiIsDisabled() {
        var beanFactory = new DefaultListableBeanFactory();
        AuthorController controller = new AuthorController(
            mock(AuthorService.class),
            mock(BookService.class),
            beanFactory.getBeanProvider(ChatClient.class));

        assertThatThrownBy(() -> controller.polishText("text"))
            .isInstanceOfSatisfying(BusinessException.class,
                exception -> org.assertj.core.api.Assertions.assertThat(exception.getResultCode())
                    .isEqualTo(ResponseStatus.AUTH_UNAVAILABLE));
    }
}
