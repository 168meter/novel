package com.java2nb.novel.auth;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HeaderUserDisplayTemplateTest {

    @Test
    void authenticatedHeaderSafelyEllipsizesLongDisplayNames() throws Exception {
        String javascript = resource("static/javascript/common.js");
        String css = resource("static/css/base.css");

        assertThat(javascript)
            .contains("var displayName = data.data.nickName || data.data.username || \"\";")
            .contains("header_user_name")
            .contains(".text(displayName).attr(\"title\", displayName)")
            .contains("href: \"javascript:logout()\"")
            .doesNotContain("data.data.nickName + \"</a>\"");

        assertThat(css)
            .contains(".bookShelf .header_user_name")
            .contains("max-width: 120px")
            .contains("overflow: hidden")
            .contains("text-overflow: ellipsis")
            .contains("white-space: nowrap")
            .contains("@media (max-width: 768px)")
            .contains("max-width: 72px");
    }

    private String resource(String path) throws IOException {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(path)) {
            assertThat(input).as("classpath resource %s", path).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
