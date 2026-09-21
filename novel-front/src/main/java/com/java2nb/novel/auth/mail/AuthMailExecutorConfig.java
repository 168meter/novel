package com.java2nb.novel.auth.mail;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration(proxyBeanMethods = false)
public class AuthMailExecutorConfig {
    @Bean("authMailExecutor")
    Executor authMailExecutor() {
        return newExecutor(2, 100);
    }

    static ThreadPoolTaskExecutor newExecutor(int threads, int queueCapacity) {
        if (threads < 1 || queueCapacity < 1) {
            throw new IllegalArgumentException("Mail executor capacity must be positive");
        }
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(threads);
        executor.setMaxPoolSize(threads);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("auth-mail-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        // A queued task has already been accepted; do not discard its verification email on restart.
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
