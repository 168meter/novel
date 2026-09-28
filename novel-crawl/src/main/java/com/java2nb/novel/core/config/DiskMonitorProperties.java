package com.java2nb.novel.core.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * @author xiongxiaoyang
 * @date 2025/10/24
 */
@Component
@ConfigurationProperties(prefix = "disk-monitor")
@Data
public class DiskMonitorProperties {

    private boolean enabled;
    private int intervalMinutes;
    private double warningThreshold;
    private double severeThreshold;
    private double criticalThreshold;
    private List<String> recipients;

}
