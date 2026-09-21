package com.java2nb.novel.auth.mail;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;

import static org.junit.jupiter.api.Assertions.*;

class AuthMailExecutorConfigTest {
    @Test
    void queuedAcceptedDeliveryDrainsOnShutdown() throws Exception {
        ThreadPoolTaskExecutor executor = AuthMailExecutorConfig.newExecutor(1, 1);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch delivered = new CountDownLatch(1);
        AtomicBoolean shutdownCompleted = new AtomicBoolean();
        try {
            executor.execute(() -> {
                entered.countDown();
                try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            executor.execute(delivered::countDown);
            Thread shutdown = new Thread(() -> { executor.shutdown(); shutdownCompleted.set(true); });
            shutdown.start();
            try {
                assertFalse(delivered.await(150, TimeUnit.MILLISECONDS));
                release.countDown();
                assertTrue(delivered.await(5, TimeUnit.SECONDS), "accepted queued delivery was discarded");
                shutdown.join(5_000);
                assertTrue(shutdownCompleted.get());
            } finally {
                release.countDown();
                shutdown.join(5_000);
            }
        } finally {
            release.countDown();
            executor.shutdown();
        }
    }

    @Test
    void smtpUses163SslTimeoutsAndEnvironmentOnlyCredentials() throws Exception {
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        var base = loader.load("base", new ClassPathResource("application.yml")).get(0);
        assertEquals("smtp.163.com", base.getProperty("spring.mail.host"));
        assertEquals(465, base.getProperty("spring.mail.port"));
        assertEquals(true, base.getProperty("spring.mail.properties.mail.smtp.ssl.enable"));
        assertEquals(5000, base.getProperty("spring.mail.properties.mail.smtp.connectiontimeout"));
        assertEquals(5000, base.getProperty("spring.mail.properties.mail.smtp.timeout"));
        assertEquals(5000, base.getProperty("spring.mail.properties.mail.smtp.writetimeout"));
        var dev = loader.load("dev", new ClassPathResource("application-dev.yml")).get(0);
        var prod = loader.load("prod", new ClassPathResource("application-prod.yml")).get(0);
        assertEquals("${MAIL_USERNAME:}", dev.getProperty("spring.mail.username"));
        assertEquals("${MAIL_PASSWORD:}", dev.getProperty("spring.mail.password"));
        assertEquals("${MAIL_USERNAME}", prod.getProperty("spring.mail.username"));
        assertEquals("${MAIL_PASSWORD}", prod.getProperty("spring.mail.password"));
    }

    @Test
    void boundedPoolRejectsImmediatelyWhenThreadAndQueueAreFull() throws Exception {
        ThreadPoolTaskExecutor executor = AuthMailExecutorConfig.newExecutor(1, 1);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            executor.execute(() -> {
                entered.countDown();
                try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            executor.execute(() -> {});
            assertThrows(RejectedExecutionException.class, () -> executor.execute(() -> {}));
            assertEquals(1, executor.getCorePoolSize());
            assertEquals(1, executor.getMaxPoolSize());
            assertEquals(1, executor.getQueueCapacity());
        } finally {
            release.countDown();
            executor.shutdown();
        }
    }
}
