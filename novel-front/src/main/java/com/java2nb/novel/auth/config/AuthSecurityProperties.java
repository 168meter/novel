package com.java2nb.novel.auth.config;

import java.time.Duration;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "novel.auth")
public class AuthSecurityProperties {

    private String hmacSecret;
    private Duration loginWindow = Duration.ofMinutes(15);
    private Duration captchaTtl = Duration.ofMinutes(10);
    private Set<String> trustedProxyAddresses = Set.of("127.0.0.1", "::1");
    private int accountFailureLimit = 5;
    private int ipCaptchaThreshold = 10;
    private int ipRequestLimit = 60;
    private Duration loginRateWindow = Duration.ofMinutes(1);
    private Duration imageCaptchaTtl = Duration.ofMinutes(5);

    public String getHmacSecret() { return hmacSecret; }
    public void setHmacSecret(String hmacSecret) { this.hmacSecret = hmacSecret; }
    public Duration getLoginWindow() { return loginWindow; }
    public void setLoginWindow(Duration loginWindow) { this.loginWindow = loginWindow; }
    public Duration getCaptchaTtl() { return captchaTtl; }
    public void setCaptchaTtl(Duration captchaTtl) { this.captchaTtl = captchaTtl; }
    public Set<String> getTrustedProxyAddresses() { return trustedProxyAddresses; }
    public void setTrustedProxyAddresses(Set<String> trustedProxyAddresses) { this.trustedProxyAddresses = trustedProxyAddresses; }
    public int getAccountFailureLimit() { return accountFailureLimit; }
    public void setAccountFailureLimit(int accountFailureLimit) { this.accountFailureLimit = accountFailureLimit; }
    public int getIpCaptchaThreshold() { return ipCaptchaThreshold; }
    public void setIpCaptchaThreshold(int ipCaptchaThreshold) { this.ipCaptchaThreshold = ipCaptchaThreshold; }
    public int getIpRequestLimit() { return ipRequestLimit; }
    public void setIpRequestLimit(int ipRequestLimit) { this.ipRequestLimit = ipRequestLimit; }
    public Duration getLoginRateWindow() { return loginRateWindow; }
    public void setLoginRateWindow(Duration loginRateWindow) { this.loginRateWindow = loginRateWindow; }
    public Duration getImageCaptchaTtl() { return imageCaptchaTtl; }
    public void setImageCaptchaTtl(Duration imageCaptchaTtl) { this.imageCaptchaTtl = imageCaptchaTtl; }
}
