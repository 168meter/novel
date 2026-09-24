package com.java2nb.novel.core.config;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class AiConfigOptionalChatTest {

    @Test
    void startsWithoutChatClientBuilderWhenChatAiIsDisabled() {
        new ApplicationContextRunner()
            .withUserConfiguration(AiConfig.class)
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).doesNotHaveBean(ChatClient.class);
            });
    }
}
