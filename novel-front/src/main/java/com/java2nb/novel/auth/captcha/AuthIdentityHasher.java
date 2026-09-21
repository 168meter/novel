package com.java2nb.novel.auth.captcha;

import com.java2nb.novel.auth.config.AuthSecurityProperties;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

@Component
public final class AuthIdentityHasher {
    private static final String ALGORITHM = "HmacSHA256";
    private final byte[] secret;

    public AuthIdentityHasher(AuthSecurityProperties properties) {
        String configured = properties.getHmacSecret();
        if (configured == null || configured.isBlank()) {
            throw new IllegalArgumentException("Authentication HMAC secret is required.");
        }
        this.secret = configured.getBytes(StandardCharsets.UTF_8);
    }

    public String emailKey(CaptchaPurpose purpose, String normalizedEmail) {
        String email = require(normalizedEmail).trim().toLowerCase(Locale.ROOT);
        return "auth:captcha:" + purpose.keyPart() + ":" + hmac("email-key\0" + purpose.name() + "\0" + email).substring(0, 32);
    }

    public String ipKey(String clientAddress) {
        return "auth:captcha:ip-hour:" + hmac("ip-key\0" + require(clientAddress)).substring(0, 32);
    }

    public String codeDigest(CaptchaPurpose purpose, String normalizedEmail, String code) {
        if (code == null || !code.matches("\\d{6}")) throw new IllegalArgumentException("Invalid captcha format.");
        return hmac("code\0" + purpose.name() + "\0"
            + require(normalizedEmail).trim().toLowerCase(Locale.ROOT) + "\0" + code);
    }

    public String loginAccountKey(String normalizedAccount) {
        return "auth:login:account:" + hmac("login-account\0" + require(normalizedAccount)
            .trim().toLowerCase(Locale.ROOT)).substring(0, 32);
    }

    public String loginIpKey(String clientAddress) {
        return "auth:login:ip:" + hmac("login-ip\0" + require(clientAddress)).substring(0, 32);
    }

    public String loginRateKey(String clientAddress) {
        return "auth:login:rate:" + hmac("login-rate\0" + require(clientAddress)).substring(0, 32);
    }

    public String imageCaptchaKey(String clientAddress) {
        return "auth:login:image-captcha:" + hmac("image-client\0" + require(clientAddress)).substring(0, 32);
    }

    public String imageCaptchaDigest(String clientAddress, String code) {
        if (code == null || !code.matches("\\d{4}")) throw new IllegalArgumentException("Invalid image captcha format");
        return hmac("image-code\0" + require(clientAddress) + "\0" + code);
    }

    private String hmac(String input) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret, ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(input.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Authentication HMAC is unavailable.", ex);
        }
    }

    private static String require(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Authentication identity is required.");
        return value;
    }
}
