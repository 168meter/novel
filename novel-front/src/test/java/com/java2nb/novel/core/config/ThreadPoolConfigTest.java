package com.java2nb.novel.core.config;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ThreadPoolConfigTest {

    @Test
    void executorMetricsExposeActiveAndQueuedWork() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ThreadPoolExecutor executor = new ThreadPoolConfig().threadPoolExecutor(properties(), registry);
        CountDownLatch taskStarted = new CountDownLatch(1);
        CountDownLatch releaseTask = new CountDownLatch(1);

        try {
            executor.execute(() -> {
                taskStarted.countDown();
                await(releaseTask);
            });
            assertThat(taskStarted.await(1, TimeUnit.SECONDS)).isTrue();
            executor.execute(() -> { });

            assertThat(registry.get("executor.active").tag("name", "novel.front.executor").gauge().value())
                    .isEqualTo(1.0);
            assertThat(registry.get("executor.queued").tag("name", "novel.front.executor").gauge().value())
                    .isEqualTo(1.0);
            assertThat(registry.get("executor.queue.remaining").tag("name", "novel.front.executor").gauge().value())
                    .isZero();
            assertThat(registry.get("executor.pool.size").tag("name", "novel.front.executor").gauge().value())
                    .isEqualTo(1.0);

            releaseTask.countDown();
            executor.shutdown();
            assertThat(executor.awaitTermination(1, TimeUnit.SECONDS)).isTrue();
            assertThat(registry.get("executor.completed").tag("name", "novel.front.executor")
                    .functionCounter().count()).isEqualTo(2.0);
        } finally {
            releaseTask.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(1, TimeUnit.SECONDS)).isTrue();
            registry.close();
        }
    }

    @Test
    void rejectedTaskIsCountedAndStillThrows() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ThreadPoolExecutor executor = new ThreadPoolConfig().threadPoolExecutor(properties(), registry);
        CountDownLatch taskStarted = new CountDownLatch(1);
        CountDownLatch releaseTask = new CountDownLatch(1);

        try {
            executor.execute(() -> {
                taskStarted.countDown();
                await(releaseTask);
            });
            assertThat(taskStarted.await(1, TimeUnit.SECONDS)).isTrue();
            executor.execute(() -> { });

            assertThatThrownBy(() -> executor.execute(() -> { }))
                    .isInstanceOf(RejectedExecutionException.class);
            assertThat(registry.get("novel.front.executor.rejections").counter().count())
                    .isEqualTo(1.0);
        } finally {
            releaseTask.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(1, TimeUnit.SECONDS)).isTrue();
            registry.close();
        }
    }

    @Test
    void prometheusUsesStableExecutorMetricNames() {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        ThreadPoolExecutor executor = new ThreadPoolConfig().threadPoolExecutor(properties(), registry);

        try {
            assertThat(registry.scrape())
                    .contains("executor_active_threads")
                    .contains("executor_queued_tasks")
                    .contains("executor_queue_remaining_tasks")
                    .contains("executor_pool_size_threads")
                    .contains("novel_front_executor_rejections_total");
        } finally {
            executor.shutdownNow();
            registry.close();
        }
    }

    private static ThreadPoolProperties properties() {
        ThreadPoolProperties properties = new ThreadPoolProperties();
        properties.setCorePoolSize(1);
        properties.setMaximumPoolSize(1);
        properties.setKeepAliveTime(10L);
        properties.setQueueSize(1);
        return properties;
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
