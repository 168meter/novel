package com.java2nb.novel.service.impl;

import java.lang.reflect.Constructor;
import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.OpenAiImageModel;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;

class BookServiceOptionalAiTest {

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Test
    void createsBookServiceWhenOpenAiImageModelIsDisabled() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(
                new MapPropertySource("test", Map.of("pic.save.path", "/tmp/pictures")));

            Constructor<?> constructor = BookServiceImpl.class.getDeclaredConstructors()[0];
            for (Class<?> dependencyType : constructor.getParameterTypes()) {
                if (dependencyType == OpenAiImageModel.class) {
                    continue;
                }
                context.registerBean(dependencyType.getName(), (Class) dependencyType,
                    () -> mock(dependencyType));
            }
            context.registerBean(BookServiceImpl.class);

            assertThatCode(context::refresh).doesNotThrowAnyException();
        }
    }
}
