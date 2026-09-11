package com.java2nb.novel.engagement;

import com.java2nb.novel.event.ReadingEngagementEvent;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class ReadingEventFingerprint {

    public byte[] fingerprint(ReadingEngagementEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        String canonical = String.join("\n",
            String.valueOf(event.version()),
            String.valueOf(event.eventId()),
            String.valueOf(event.bookId()),
            String.valueOf(event.chapterId()),
            String.valueOf(event.creditedSeconds()),
            String.valueOf(event.occurredAt()),
            String.valueOf(event.statDate()));
        return sha256(canonical.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] sha256(byte[] canonicalBytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(canonicalBytes);
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable", unavailable);
        }
    }
}
