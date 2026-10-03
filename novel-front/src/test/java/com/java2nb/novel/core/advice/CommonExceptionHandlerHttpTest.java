package com.java2nb.novel.core.advice;

import io.github.xxyopen.model.resp.RestResult;
import java.util.Locale;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.View;
import org.springframework.web.servlet.ViewResolver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CommonExceptionHandlerHttpTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        View errorView = (model, request, response) -> {
            response.setContentType(MediaType.TEXT_HTML_VALUE);
            response.getWriter().write("error-page-marker");
        };
        ViewResolver resolver = (String viewName, Locale locale) -> errorView;
        mockMvc = MockMvcBuilders.standaloneSetup(new FailingPageController(), new FailingApiController())
            .setControllerAdvice(new CommonExceptionHandler())
            .setViewResolvers(resolver)
            .build();
    }

    @Test
    void unexpectedPageFailureReturnsHtml500WithoutExposingException() throws Exception {
        String body = mockMvc.perform(get("/test/page-failure").accept(MediaType.TEXT_HTML))
            .andExpect(status().isInternalServerError())
            .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
            .andExpect(content().string("error-page-marker"))
            .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("private failure detail", "IllegalStateException");
    }

    @Test
    void pageFailureDoesNotSelectJsonWhenAcceptExplicitlyRejectsIt() throws Exception {
        mockMvc.perform(get("/test/page-failure")
                .header("Accept", "text/html, application/json;q=0"))
            .andExpect(status().isInternalServerError())
            .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
            .andExpect(content().string("error-page-marker"));
    }

    @Test
    void unexpectedApiFailureReturnsJson500WithoutExposingException() throws Exception {
        String body = mockMvc.perform(get("/test/api-failure"))
            .andExpect(status().isInternalServerError())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("\"code\"").doesNotContain("private failure detail", "IllegalStateException");
    }

    @Test
    void apiTypeConversionFailureReturnsJson400() throws Exception {
        mockMvc.perform(get("/test/api-number/not-a-number"))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
    }

    @Controller
    static class FailingPageController {
        @GetMapping("/test/page-failure")
        String fail() {
            throw new IllegalStateException("private failure detail");
        }
    }

    @RestController
    static class FailingApiController {
        @GetMapping("/test/api-failure")
        RestResult<Void> fail() {
            throw new IllegalStateException("private failure detail");
        }

        @GetMapping("/test/api-number/{value}")
        RestResult<Long> number(@PathVariable Long value) {
            return RestResult.ok(value);
        }
    }
}
