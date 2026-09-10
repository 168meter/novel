package com.java2nb.novel.core.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.jvm.ExecutorServiceMetrics;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 线程池配置
 * @author xiongxiaoyang
 */
@Configuration
public class ThreadPoolConfig {

    @Bean
    public ThreadPoolExecutor threadPoolExecutor(ThreadPoolProperties properties, MeterRegistry meterRegistry){
        Counter rejectionCounter = meterRegistry.counter("novel.front.executor.rejections");
        RejectedExecutionHandler abortPolicy = new ThreadPoolExecutor.AbortPolicy();
        RejectedExecutionHandler meteredAbortPolicy = (task, executor) -> {
            rejectionCounter.increment();
            abortPolicy.rejectedExecution(task, executor);
        };
        ThreadPoolExecutor executor = new ThreadPoolExecutor(properties.getCorePoolSize(),properties.getMaximumPoolSize(),properties.getKeepAliveTime()
                , TimeUnit.SECONDS, new LinkedBlockingDeque<>(properties.getQueueSize()), meteredAbortPolicy);
        ExecutorServiceMetrics.monitor(meterRegistry, executor, "novel.front.executor");
        return executor;
    }

}
