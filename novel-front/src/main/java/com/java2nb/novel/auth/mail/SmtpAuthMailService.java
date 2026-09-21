package com.java2nb.novel.auth.mail;

import com.java2nb.novel.auth.captcha.CaptchaPurpose;
import com.java2nb.novel.auth.captcha.CaptchaService;
import com.java2nb.novel.auth.metrics.AuthenticationMetrics;
import com.java2nb.novel.auth.metrics.AuthenticationMetrics.MailOutcome;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
public class SmtpAuthMailService implements AuthMailService {
    private static final Logger log = LoggerFactory.getLogger(SmtpAuthMailService.class);
    private final JavaMailSender sender;
    private final Executor executor;
    private final CaptchaService captcha;
    private final AuthenticationMetrics metrics;
    private final String from;

    public SmtpAuthMailService(JavaMailSender sender,
                               @Qualifier("authMailExecutor") Executor executor,
                               CaptchaService captcha, AuthenticationMetrics metrics,
                               @Value("${spring.mail.username:}") String from) {
        this.sender = sender;
        this.executor = executor;
        this.captcha = captcha;
        this.metrics = metrics;
        this.from = from;
    }

    @Override
    public boolean submit(CaptchaPurpose purpose, String normalizedEmail, String code, boolean deliver) {
        Objects.requireNonNull(purpose, "purpose");
        Objects.requireNonNull(normalizedEmail, "normalizedEmail");
        if (deliver && (code == null || !code.matches("[0-9]{6}"))) {
            throw new IllegalArgumentException("Invalid verification code");
        }
        try {
            executor.execute(() -> {
                if (!deliver) return;
                try {
                    if (from == null || from.isBlank()) throw new IllegalStateException("Mail sender is not configured");
                    SimpleMailMessage message = new SimpleMailMessage();
                    message.setFrom(from);
                    message.setTo(normalizedEmail);
                    message.setSubject(subject(purpose));
                    message.setText("您的验证码是 " + code + "，10 分钟内有效。请勿将验证码透露给他人。");
                    sender.send(message);
                    metrics.mail(purpose, MailOutcome.SUCCESS);
                } catch (RuntimeException failure) {
                    metrics.mail(purpose, MailOutcome.FAILED);
                    revokeSafely(purpose, normalizedEmail, code);
                    log.warn("Authentication email delivery failed; purpose={}; cause={}", purpose.keyPart(),
                        failure.getClass().getSimpleName());
                }
            });
            return true;
        } catch (RejectedExecutionException rejected) {
            metrics.mail(purpose, MailOutcome.REJECTED);
            if (deliver) revokeSafely(purpose, normalizedEmail, code);
            log.warn("Authentication email queue rejected task; purpose={}", purpose.keyPart());
            return false;
        }
    }

    private void revokeSafely(CaptchaPurpose purpose, String normalizedEmail, String code) {
        try {
            captcha.revoke(purpose, normalizedEmail, code);
        } catch (RuntimeException failure) {
            log.warn("Authentication captcha revocation failed; purpose={}; cause={}", purpose.keyPart(),
                failure.getClass().getSimpleName());
        }
    }

    private String subject(CaptchaPurpose purpose) {
        return switch (purpose) {
            case REGISTER -> "小说精品屋注册验证码";
            case RESET_PASSWORD -> "小说精品屋重置密码验证码";
            case CHANGE_EMAIL -> "小说精品屋修改邮箱验证码";
        };
    }
}
