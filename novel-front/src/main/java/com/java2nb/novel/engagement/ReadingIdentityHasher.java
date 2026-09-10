package com.java2nb.novel.engagement;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

@Component
public class ReadingIdentityHasher {

    private static final HexFormat HEX_FORMAT = HexFormat.of();
    private static final String HMAC_SHA_256 = "HmacSHA256";

    private final String ipHmacSecret;

    public ReadingIdentityHasher(ReadingEngagementProperties properties) {
        this.ipHmacSecret = properties.ipHmacSecret();
    }

    public String sessionHash(String rawSessionId) {
        requireNonBlank(rawSessionId);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HEX_FORMAT.formatHex(digest.digest(rawSessionId.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public String ipHmac(String rawIpAddress) {
        requireNonBlank(rawIpAddress);
        try {
            Mac mac = Mac.getInstance(HMAC_SHA_256);
            mac.init(new SecretKeySpec(ipHmacSecret.getBytes(StandardCharsets.UTF_8), HMAC_SHA_256));
            return HEX_FORMAT.formatHex(mac.doFinal(rawIpAddress.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HmacSHA256 is unavailable", exception);
        }
    }

    private void requireNonBlank(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            throw new IllegalArgumentException("Identity value must not be blank");
        }
    }
}
