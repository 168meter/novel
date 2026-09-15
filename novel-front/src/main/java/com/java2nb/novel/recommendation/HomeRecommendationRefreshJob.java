package com.java2nb.novel.recommendation;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ScheduledFuture;
import org.springframework.scheduling.TaskScheduler;

public class HomeRecommendationRefreshJob implements AutoCloseable {
    private final HomeRecommendationService service;
    private final TaskScheduler scheduler;
    private ScheduledFuture<?> future;
    public HomeRecommendationRefreshJob(HomeRecommendationService service,TaskScheduler scheduler) { this.service=service; this.scheduler=scheduler; }
    public void start() { future=scheduler.scheduleWithFixedDelay(service::refresh,Instant.now(),Duration.ofMinutes(5)); }
    public void refresh() { service.refresh(); }
    @Override public void close() { if(future!=null) future.cancel(false); }
}
