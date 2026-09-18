package com.java2nb.novel.auth.config;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "novel.auth.password")
public class AuthPasswordProperties {

    private int saltLength = 16;
    private int hashLength = 32;
    private int memoryKiB = 19456;
    private int iterations = 2;
    private int parallelism = 1;

    @PostConstruct
    public void validate() {
        if (saltLength < 16) {
            throw new IllegalStateException("Argon2 salt length is below the security minimum.");
        }
        if (hashLength < 32) {
            throw new IllegalStateException("Argon2 hash length is below the security minimum.");
        }
        if (memoryKiB < 19456 || memoryKiB > 262144) {
            throw new IllegalStateException("Argon2 memory cost is outside the allowed range.");
        }
        if (iterations < 2 || iterations > 10) {
            throw new IllegalStateException("Argon2 iterations are outside the allowed range.");
        }
        if (parallelism < 1 || parallelism > 8) {
            throw new IllegalStateException("Argon2 parallelism is outside the allowed range.");
        }
    }

    public int getSaltLength() { return saltLength; }
    public void setSaltLength(int saltLength) { this.saltLength = saltLength; }
    public int getHashLength() { return hashLength; }
    public void setHashLength(int hashLength) { this.hashLength = hashLength; }
    public int getMemoryKiB() { return memoryKiB; }
    public void setMemoryKiB(int memoryKiB) { this.memoryKiB = memoryKiB; }
    public int getIterations() { return iterations; }
    public void setIterations(int iterations) { this.iterations = iterations; }
    public int getParallelism() { return parallelism; }
    public void setParallelism(int parallelism) { this.parallelism = parallelism; }
}
