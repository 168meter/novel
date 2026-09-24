package com.java2nb.novel.engagement;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class ReadingHeartbeatTemplateTest {

    private static final String DATA_ELEMENT = """
        <div id="reading-engagement"
             th:if="${readingPageVisitId != null}"
             th:data-book-id="${book.id}"
             th:data-chapter-id="${bookIndex.id}"
             th:data-page-visit-id="${readingPageVisitId}"
             hidden></div>
        """;

    private static final String MODULE_TAG =
        "<script type=\"module\" src=\"/javascript/reading-heartbeat.mjs\"></script>";

    @ParameterizedTest
    @ValueSource(strings = {
        "templates/book/book_content.html",
        "templates/mobile/book/book_content.html"
    })
    void chapterTemplateUsesOneSharedConditionalReadingHeartbeat(String resourcePath) throws IOException {
        String template = normalizeLineEndings(readClasspathResource(resourcePath));

        assertThat(template).containsOnlyOnce(normalizeLineEndings(DATA_ELEMENT));
        assertThat(template).containsOnlyOnce(MODULE_TAG);
        assertThat(template)
            .doesNotContain("/engagement/reading/heartbeat")
            .doesNotContain("createReadingHeartbeat");
    }

    private String readClasspathResource(String resourcePath) throws IOException {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            assertThat(input).as("classpath resource %s", resourcePath).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String normalizeLineEndings(String value) {
        return value.replace("\r\n", "\n").replace('\r', '\n');
    }
}
