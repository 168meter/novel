package com.java2nb.novel.core.advice;

import org.junit.jupiter.api.Test;
import org.springframework.http.converter.ByteArrayHttpMessageConverter;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;

import static org.assertj.core.api.Assertions.assertThat;

class CustomResponseBodyAdviceTest {

    private final CustomResponseBodyAdvice advice =
        new CustomResponseBodyAdvice(new Jackson2ObjectMapperBuilder());

    @Test
    void byteArrayResponsesBypassJsonLongConversion() {
        assertThat(advice.supports(null, ByteArrayHttpMessageConverter.class)).isFalse();
    }

    @Test
    void jacksonResponsesUseJsonLongConversion() {
        assertThat(advice.supports(null, MappingJackson2HttpMessageConverter.class)).isTrue();
    }
}
