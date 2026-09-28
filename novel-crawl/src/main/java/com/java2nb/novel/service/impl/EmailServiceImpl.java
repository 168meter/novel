package com.java2nb.novel.service.impl;

import com.java2nb.novel.service.EmailService;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * @author xiongxiaoyang
 * @date 2025/10/24
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailServiceImpl implements EmailService {

    private final JavaMailSender javaMailSender;
    private final TemplateEngine templateEngine;

    @Value("${spring.mail.username}")
    private String fromEmail;

    @Value("${spring.mail.nickname}")
    private String nickName;

    @Override
    public void sendHtmlEmail(String subject, String templateName, Map<String, Object> templateModel, List<String> to) {
        try {
            MimeMessage message = javaMailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(
                message,
                MimeMessageHelper.MULTIPART_MODE_MIXED_RELATED,
                StandardCharsets.UTF_8.name());

            Context context = new Context();
            context.setVariables(templateModel);

            String htmlBody = templateEngine.process(templateName, context);

            helper.setFrom(new InternetAddress(fromEmail, nickName, "UTF-8"));
            helper.setTo(to.toArray(new String[0]));
            helper.setSubject(subject);
            helper.setText(htmlBody, true);

            javaMailSender.send(message);
        } catch (Exception e) {
            log.error("发送邮件失败");
            log.error(e.getMessage(), e);
        }
    }
}
