package com.java2nb.novel.config;

import com.java2nb.novel.engagement.ReadingEngagementProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ReadingEngagementProperties.class)
public class ReadingEngagementConfig {
}
