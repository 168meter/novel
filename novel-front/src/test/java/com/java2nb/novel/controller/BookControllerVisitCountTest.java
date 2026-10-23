package com.java2nb.novel.controller;

import com.java2nb.novel.messaging.BookVisitEventPublisher;
import com.java2nb.novel.service.BookService;
import com.java2nb.novel.service.IpLocationService;
import com.java2nb.novel.service.LikeService;
import io.github.xxyopen.model.resp.RestResult;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BookControllerVisitCountTest {

    @Test
    void publishesVisitWithoutUpdatingDatabaseSynchronously() {
        BookService bookService = mock(BookService.class);
        BookVisitEventPublisher publisher = mock(BookVisitEventPublisher.class);
        BookController controller = new BookController(
            bookService,
            Map.of(),
            mock(IpLocationService.class),
            mock(LikeService.class),
            publisher);

        when(publisher.publish(42L)).thenReturn(true);
        var result = controller.addVisitCount(42L);

        assertThat(result.getStatusCode().value()).isEqualTo(202);
        assertThat(result.getBody().getCode()).isEqualTo(200);
        assertThat(result.getBody().getMsg()).contains("尚未确认");
        verify(publisher).publish(42L);
        verify(bookService, never()).addVisitCount(anyLong(), anyInt());
    }

    @Test
    void rejectsInvalidInputAndReportsKnownSendFailureWithoutDatabaseFallback() {
        BookService service = mock(BookService.class);
        BookVisitEventPublisher publisher = mock(BookVisitEventPublisher.class);
        var controller = new BookController(service, Map.of(), mock(IpLocationService.class), mock(LikeService.class), publisher);
        assertThat(controller.addVisitCount(null).getStatusCode().value()).isEqualTo(400);
        assertThat(controller.addVisitCount(0L).getStatusCode().value()).isEqualTo(400);
        org.mockito.Mockito.verifyNoInteractions(publisher);
        when(publisher.publish(42L)).thenReturn(false);
        var rejected = controller.addVisitCount(42L);
        assertThat(rejected.getStatusCode().value()).isEqualTo(503);
        assertThat(rejected.getBody().getCode()).isNotEqualTo(200);
        org.mockito.Mockito.verifyNoInteractions(service);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void acceptedHttpResponseDoesNotBecomeAClaimOfCompensationOnLateFailure() {
        var registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        org.springframework.kafka.core.KafkaTemplate template = mock(org.springframework.kafka.core.KafkaTemplate.class);
        var pending = new java.util.concurrent.CompletableFuture<org.springframework.kafka.support.SendResult<Long, com.java2nb.novel.event.BookVisitEvent>>();
        when(template.send(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(com.java2nb.novel.event.BookVisitEvent.class))).thenReturn(pending);
        var publisher = new BookVisitEventPublisher(template,
            new com.java2nb.novel.config.BookVisitKafkaProperties("visit-topic", "visit-dlt", "group", 500),
            registry, java.time.Clock.systemUTC());
        var service = mock(BookService.class);
        var controller = new BookController(service, Map.of(), mock(IpLocationService.class), mock(LikeService.class), publisher);
        var response = controller.addVisitCount(42L);
        assertThat(response.getStatusCode().value()).isEqualTo(202);
        pending.completeExceptionally(new org.apache.kafka.common.KafkaException("broker unavailable"));
        assertThat(registry.counter("novel.book.visit.kafka.send", "result", "failed").count()).isEqualTo(1);
        assertThat(response.getBody().getMsg()).contains("尚未确认");
        org.mockito.Mockito.verifyNoInteractions(service);
    }
}
