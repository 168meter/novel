package com.java2nb.novel.recommendation;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TaskScheduler;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class HomeRecommendationRefreshJobTest {
    @Test void schedulesImmediateFixedDelayRefreshAndCancelsOnShutdown() {
        var service=mock(HomeRecommendationService.class); var scheduler=mock(TaskScheduler.class);
        var future=mock(java.util.concurrent.ScheduledFuture.class);
        when(scheduler.scheduleWithFixedDelay(any(Runnable.class),any(java.time.Instant.class),eq(Duration.ofMinutes(5))))
            .thenReturn(future);
        var job=new HomeRecommendationRefreshJob(service,scheduler);
        job.start();
        var task=org.mockito.ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).scheduleWithFixedDelay(task.capture(),any(java.time.Instant.class),eq(Duration.ofMinutes(5)));
        task.getValue().run(); verify(service).refresh();
        job.close(); verify(future).cancel(false);
    }
}
