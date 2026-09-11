package com.java2nb.novel.engagement;

import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import java.time.ZoneId;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "novel.reading-engagement")
@Validated
public class ReadingEngagementProperties {

    private Duration pageTtl = Duration.ofHours(2);
    private Duration rateWindow = Duration.ofSeconds(60);
    private Duration rateKeyTtl = Duration.ofMinutes(2);
    private Duration creditKeyTtl = Duration.ofDays(2);
    private int sessionLimit = 2;
    private int ipLimit = 120;
    private int dailyCapSeconds = 1800;
    private int creditedSeconds = 30;
    private ZoneId zoneId = ZoneId.of("Asia/Shanghai");
    @NotBlank
    private String ipHmacSecret;
    private Set<String> trustedProxyAddresses = Set.of("127.0.0.1", "::1");

    public Duration pageTtl() {
        return pageTtl;
    }

    public void setPageTtl(Duration pageTtl) {
        this.pageTtl = pageTtl;
    }

    public Duration rateWindow() {
        return rateWindow;
    }

    public void setRateWindow(Duration rateWindow) {
        this.rateWindow = rateWindow;
    }

    public Duration rateKeyTtl() {
        return rateKeyTtl;
    }

    public void setRateKeyTtl(Duration rateKeyTtl) {
        this.rateKeyTtl = rateKeyTtl;
    }

    public Duration creditKeyTtl() {
        return creditKeyTtl;
    }

    public void setCreditKeyTtl(Duration creditKeyTtl) {
        this.creditKeyTtl = creditKeyTtl;
    }

    public int sessionLimit() {
        return sessionLimit;
    }

    public void setSessionLimit(int sessionLimit) {
        this.sessionLimit = sessionLimit;
    }

    public int ipLimit() {
        return ipLimit;
    }

    public void setIpLimit(int ipLimit) {
        this.ipLimit = ipLimit;
    }

    public int dailyCapSeconds() {
        return dailyCapSeconds;
    }

    public void setDailyCapSeconds(int dailyCapSeconds) {
        this.dailyCapSeconds = dailyCapSeconds;
    }

    public int creditedSeconds() {
        return creditedSeconds;
    }

    public void setCreditedSeconds(int creditedSeconds) {
        this.creditedSeconds = creditedSeconds;
    }

    public ZoneId zoneId() {
        return zoneId;
    }

    public void setZoneId(ZoneId zoneId) {
        this.zoneId = zoneId;
    }

    public String ipHmacSecret() {
        return ipHmacSecret;
    }

    public void setIpHmacSecret(String ipHmacSecret) {
        this.ipHmacSecret = ipHmacSecret;
    }

    public Set<String> trustedProxyAddresses() {
        return trustedProxyAddresses;
    }

    public void setTrustedProxyAddresses(Set<String> trustedProxyAddresses) {
        this.trustedProxyAddresses = trustedProxyAddresses;
    }
}
