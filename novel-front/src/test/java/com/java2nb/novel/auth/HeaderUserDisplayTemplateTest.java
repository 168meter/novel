package com.java2nb.novel.auth;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HeaderUserDisplayTemplateTest {

    @Test
    void authenticatedHeaderSafelyEllipsizesLongDisplayNames() throws Exception {
        String javascript = resource("static/javascript/common.js");
        String css = resource("static/css/base.css");
        String productionJavascript = productionResource("javascript/common.js");
        String productionCss = productionResource("css/base.css");

        assertJavascriptContract(javascript);
        assertJavascriptContract(productionJavascript);
        assertCssContract(css);
        assertCssContract(productionCss);
        assertThat(productionJavascript).isEqualTo(javascript);
        assertThat(productionCss).isEqualTo(css);
    }

    private void assertJavascriptContract(String javascript) {
        assertThat(javascript)
            .contains("var displayName = data.data.nickName || data.data.username || \"\";")
            .contains("header_user_name")
            .contains(".text(displayName).attr(\"title\", displayName)")
            .contains("href: \"javascript:logout()\"")
            .doesNotContain("data.data.nickName + \"</a>\"");
    }

    private void assertCssContract(String css) {
        assertThat(css)
            .contains(".bookShelf .header_user_name")
            .contains("max-width: 120px")
            .contains("overflow: hidden")
            .contains("text-overflow: ellipsis")
            .contains("white-space: nowrap")
            .contains("@media (max-width: 768px)")
            .contains("max-width: 72px");
    }

    private String productionResource(String path) throws IOException {
        for (Path root : List.of(Path.of("templates"), Path.of("..", "templates"))) {
            Path resource = root.resolve(Path.of("green", "static", path));
            if (Files.isRegularFile(resource)) {
                return Files.readString(resource, StandardCharsets.UTF_8);
            }
        }
        throw new IOException("Production template resource not found: " + path);
    }

    private String resource(String path) throws IOException {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(path)) {
            assertThat(input).as("classpath resource %s", path).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
