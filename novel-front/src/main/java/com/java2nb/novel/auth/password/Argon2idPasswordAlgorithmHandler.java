package com.java2nb.novel.auth.password;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;

public final class Argon2idPasswordAlgorithmHandler implements PasswordAlgorithmHandler {
    private static final Pattern PARAMETERS = Pattern.compile(
        "^\\$argon2id\\$v=\\d+\\$m=(\\d+),t=(\\d+),p=(\\d+)\\$[^$]+\\$[^$]+$");
    private final Argon2PasswordEncoder encoder;
    private final int memoryKiB;
    private final int iterations;
    private final int parallelism;

    public Argon2idPasswordAlgorithmHandler(Argon2PasswordEncoder encoder,
                                            int memoryKiB, int iterations, int parallelism) {
        this.encoder = encoder;
        this.memoryKiB = memoryKiB;
        this.iterations = iterations;
        this.parallelism = parallelism;
    }

    @Override public String algorithm() { return PasswordAlgorithm.ARGON2ID.name(); }

    @Override public PasswordHash encode(String rawPassword) {
        requireRaw(rawPassword);
        return new PasswordHash(encoder.encode(rawPassword), algorithm());
    }

    @Override public boolean matches(String rawPassword, String encodedPassword) {
        requireRaw(rawPassword);
        if (encodedPassword == null || encodedPassword.isBlank()) return false;
        if (!PARAMETERS.matcher(encodedPassword).matches()) return false;
        try {
            return encoder.matches(rawPassword, encodedPassword);
        } catch (RuntimeException ex) {
            return false;
        }
    }

    @Override public boolean needsUpgrade(String encodedPassword) {
        if (encodedPassword == null || encodedPassword.isBlank()) throw new IllegalArgumentException("Password hash is required.");
        Matcher matcher = PARAMETERS.matcher(encodedPassword);
        if (!matcher.matches()) throw new IllegalArgumentException("Malformed password hash.");
        try {
            int memory = Integer.parseInt(matcher.group(1));
            int time = Integer.parseInt(matcher.group(2));
            int parallel = Integer.parseInt(matcher.group(3));
            return memory < memoryKiB || time < iterations || parallel < parallelism;
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("Malformed password hash.");
        }
    }

    private static void requireRaw(String rawPassword) {
        if (rawPassword == null || rawPassword.isEmpty()) throw new IllegalArgumentException("Password is required.");
    }
}
