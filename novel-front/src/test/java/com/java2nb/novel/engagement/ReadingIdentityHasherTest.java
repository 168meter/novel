package com.java2nb.novel.engagement;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class ReadingIdentityHasherTest {

    @Test
    void producesStableDistinctLowercaseSha256SessionHashes() {
        ReadingIdentityHasher hasher = new ReadingIdentityHasher(properties("first-secret"));

        String first = hasher.sessionHash("session-a");

        assertThat(first).isEqualTo(hasher.sessionHash("session-a"));
        assertThat(first).isNotEqualTo(hasher.sessionHash("session-b"));
        assertThat(first).matches("[0-9a-f]{64}");
    }

    @Test
    void producesStableDistinctLowercaseHmacIpHashes() {
        String ip = "198.51.100.20";
        ReadingIdentityHasher firstSecretHasher = new ReadingIdentityHasher(properties("first-secret"));
        ReadingIdentityHasher secondSecretHasher = new ReadingIdentityHasher(properties("second-secret"));

        String hash = firstSecretHasher.ipHmac(ip);

        assertThat(hash).isEqualTo(firstSecretHasher.ipHmac(ip));
        assertThat(hash).isNotEqualTo(firstSecretHasher.ipHmac("198.51.100.21"));
        assertThat(hash).isNotEqualTo(secondSecretHasher.ipHmac(ip));
        assertThat(hash).matches("[0-9a-f]{64}");
    }

    @Test
    void rejectsBlankIdentityInputs() {
        ReadingIdentityHasher hasher = new ReadingIdentityHasher(properties("first-secret"));

        assertThatIllegalArgumentException().isThrownBy(() -> hasher.sessionHash(" "));
        assertThatIllegalArgumentException().isThrownBy(() -> hasher.ipHmac(""));
    }

    private ReadingEngagementProperties properties(String secret) {
        ReadingEngagementProperties properties = new ReadingEngagementProperties();
        properties.setIpHmacSecret(secret);
        return properties;
    }
}
