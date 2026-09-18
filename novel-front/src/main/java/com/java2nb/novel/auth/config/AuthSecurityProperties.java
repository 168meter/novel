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

    public String getHmacSecret() { return hmacSecret; }
    public void setHmacSecret(String hmacSecret) { this.hmacSecret = hmacSecret; }
    public Duration getLoginWindow() { return loginWindow; }
    public void setLoginWindow(Duration loginWindow) { this.loginWindow = loginWindow; }
    public Duration getCaptchaTtl() { return captchaTtl; }
    public void setCaptchaTtl(Duration captchaTtl) { this.captchaTtl = captchaTtl; }
    public Set<String> getTrustedProxyAddresses() { return trustedProxyAddresses; }
    public void setTrustedProxyAddresses(Set<String> trustedProxyAddresses) { this.trustedProxyAddresses = trustedProxyAddresses; }
}
