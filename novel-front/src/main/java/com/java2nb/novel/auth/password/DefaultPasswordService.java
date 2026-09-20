package com.java2nb.novel.auth.password;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class DefaultPasswordService implements PasswordService {
    private final Map<String, PasswordAlgorithmHandler> handlers;
    private final PasswordAlgorithmHandler current;

    public DefaultPasswordService(List<PasswordAlgorithmHandler> handlers) {
        if (handlers == null || handlers.isEmpty()) throw new IllegalArgumentException("Password handlers are required.");
        this.handlers = handlers.stream().collect(Collectors.toUnmodifiableMap(
            h -> validateAlgorithm(h.algorithm()), Function.identity(), (a, b) -> {
                throw new IllegalArgumentException("Duplicate password algorithm handler.");
            }));
        this.current = requireHandler(PasswordAlgorithm.ARGON2ID.name());
    }

    @Override public PasswordHash encode(String rawPassword) {
        return current.encode(rawPassword);
    }

    @Override public boolean matches(String rawPassword, String encodedPassword, String algorithm) {
        return requireHandler(algorithm).matches(rawPassword, encodedPassword);
    }

    @Override public boolean needsUpgrade(String encodedPassword, String algorithm) {
        return requireHandler(algorithm).needsUpgrade(encodedPassword);
    }

    private PasswordAlgorithmHandler requireHandler(String algorithm) {
        String key = validateAlgorithm(algorithm);
        PasswordAlgorithmHandler handler = handlers.get(key);
        if (handler == null) throw new IllegalArgumentException("Unsupported password algorithm.");
        return handler;
    }

    private static String validateAlgorithm(String algorithm) {
        if (algorithm == null || algorithm.isEmpty() || !algorithm.equals(algorithm.trim())) {
            throw new IllegalArgumentException("Password algorithm is required.");
        }
        try { return PasswordAlgorithm.valueOf(algorithm).name(); }
        catch (IllegalArgumentException ex) { throw new IllegalArgumentException("Unsupported password algorithm."); }
    }
}
