package com.java2nb.novel.config;

import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(BookVisitKafkaProperties.class)
public class BookVisitKafkaConfig {

    /**
     * Injectable clock keeps event timestamps deterministic in tests.
     */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
