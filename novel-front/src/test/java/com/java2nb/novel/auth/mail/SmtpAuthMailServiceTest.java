package com.java2nb.novel.auth.mail;

import com.java2nb.novel.auth.captcha.CaptchaPurpose;
import com.java2nb.novel.auth.captcha.CaptchaService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(OutputCaptureExtension.class)
class SmtpAuthMailServiceTest {
    private final JavaMailSender sender = mock(JavaMailSender.class);
    private final CaptchaService captcha = mock(CaptchaService.class);
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();

    @Test
    void sendsPurposeSpecificMessageWithoutPuttingSecretsInMetrics() {
        SmtpAuthMailService service = new SmtpAuthMailService(sender, Runnable::run, captcha, metrics, "sender@163.com");
        assertTrue(service.submit(CaptchaPurpose.REGISTER, "reader@example.com", "123456", true));
        var message = org.mockito.ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(sender).send(message.capture());
        assertEquals("sender@163.com", message.getValue().getFrom());
        assertArrayEquals(new String[]{"reader@example.com"}, message.getValue().getTo());
        assertTrue(message.getValue().getSubject().contains("注册"));
        assertTrue(message.getValue().getText().contains("123456"));
        assertEquals(1, metrics.counter("novel.auth.mail.delivery", "purpose", "register", "result", "success").count());
        assertFalse(metrics.getMeters().toString().contains("reader@example.com"));
        assertFalse(metrics.getMeters().toString().contains("123456"));
    }

    @Test
    void sendFailureRevokesOnlyThisCodeAndDoesNotLeakIt(CapturedOutput output) {
        doThrow(new IllegalStateException("SMTP failed for reader@example.com code 123456"))
            .when(sender).send(any(SimpleMailMessage.class));
        SmtpAuthMailService service = new SmtpAuthMailService(sender, Runnable::run, captcha, metrics, "sender@163.com");
        assertTrue(service.submit(CaptchaPurpose.RESET_PASSWORD, "reader@example.com", "123456", true));
        verify(captcha).revoke(CaptchaPurpose.RESET_PASSWORD, "reader@example.com", "123456");
        assertEquals(1, metrics.counter("novel.auth.mail.delivery", "purpose", "reset_password", "result", "failed").count());
        assertFalse(output.toString().contains("reader@example.com"));
        assertFalse(output.toString().contains("123456"));
    }

    @Test
    void rejectedQueueImmediatelyRevokesCode(CapturedOutput output) {
        Executor rejected = task -> { throw new RejectedExecutionException("queue full"); };
        SmtpAuthMailService service = new SmtpAuthMailService(sender, rejected, captcha, metrics, "sender@163.com");
        assertFalse(service.submit(CaptchaPurpose.CHANGE_EMAIL, "reader@example.com", "123456", true));
        verify(captcha).revoke(CaptchaPurpose.CHANGE_EMAIL, "reader@example.com", "123456");
        verifyNoInteractions(sender);
        assertEquals(1, metrics.counter("novel.auth.mail.delivery", "purpose", "change_email", "result", "rejected").count());
        assertFalse(output.toString().contains("reader@example.com"));
        assertFalse(output.toString().contains("123456"));
    }

    @Test
    void nonexistentAccountStillUsesSameExecutorButDoesNotSend() {
        AtomicInteger scheduled = new AtomicInteger();
        Executor executor = task -> { scheduled.incrementAndGet(); task.run(); };
        SmtpAuthMailService service = new SmtpAuthMailService(sender, executor, captcha, metrics, "sender@163.com");
        assertTrue(service.submit(CaptchaPurpose.RESET_PASSWORD, "reader@example.com", null, false));
        assertEquals(1, scheduled.get());
        verifyNoInteractions(sender, captcha);
    }
}
