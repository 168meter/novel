package com.java2nb.novel.engagement;

import java.time.Duration;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.java2nb.novel.config.ReadingEngagementConfig;

import static org.assertj.core.api.Assertions.assertThat;

class ReadingEngagementPropertiesTest {

    private final ApplicationContextRunner configuredContext = new ApplicationContextRunner()
        .withUserConfiguration(ReadingEngagementConfig.class)
        .withPropertyValues("novel.reading-engagement.ip-hmac-secret=test-secret");

    @Test
    void bindsSecretAndApprovedDefaults() {
        configuredContext.run(context -> {
            ReadingEngagementProperties properties = context.getBean(ReadingEngagementProperties.class);

            assertThat(properties.topic()).isEqualTo("novel-reading-engagement-v1");
            assertThat(properties.pageTtl()).isEqualTo(Duration.ofHours(2));
            assertThat(properties.rateWindow()).isEqualTo(Duration.ofSeconds(60));
            assertThat(properties.rateKeyTtl()).isEqualTo(Duration.ofMinutes(2));
            assertThat(properties.creditKeyTtl()).isEqualTo(Duration.ofDays(2));
            assertThat(properties.sessionLimit()).isEqualTo(2);
            assertThat(properties.ipLimit()).isEqualTo(120);
            assertThat(properties.dailyCapSeconds()).isEqualTo(1800);
            assertThat(properties.creditedSeconds()).isEqualTo(30);
            assertThat(properties.zoneId()).isEqualTo(ZoneId.of("Asia/Shanghai"));
        });
    }

    @Test
    void refusesToStartWithoutIpHmacSecret() {
        new ApplicationContextRunner()
            .withUserConfiguration(ReadingEngagementConfig.class)
            .run(context -> {
                assertThat(context).hasFailed();
            });
    }
}
