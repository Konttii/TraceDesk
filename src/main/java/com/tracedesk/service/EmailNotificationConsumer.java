package com.tracedesk.service;

import com.tracedesk.config.RabbitMqConfig;
import com.tracedesk.web.dto.EmailMessageEvent;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

@Service
public class EmailNotificationConsumer {

    private static final Logger log = LoggerFactory.getLogger(EmailNotificationConsumer.class);

    private final JavaMailSender mailSender;
    private final TemplateEngine templateEngine;
    private final String fromEmail;
    private final String senderName;

    public EmailNotificationConsumer(
            JavaMailSender mailSender,
            TemplateEngine templateEngine,
            @Value("${app.mail.from:noreply@tracedesk.kz}") String fromEmail,
            @Value("${app.mail.sender-name:TraceDesk Security}") String senderName
    ) {
        this.mailSender = mailSender;
        this.templateEngine = templateEngine;
        this.fromEmail = fromEmail;
        this.senderName = senderName;
    }

    @RabbitListener(queues = RabbitMqConfig.EMAIL_QUEUE)
    public void handleEmailMessage(EmailMessageEvent event) {
        try {
            Context context = new Context(Locale.forLanguageTag("ru"));
            if (event.variables() != null) {
                context.setVariables(event.variables());
            }

            String htmlBody = templateEngine.process(event.templateName(), context);

            MimeMessage mimeMessage = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(
                    mimeMessage,
                    MimeMessageHelper.MULTIPART_MODE_MIXED_RELATED,
                    StandardCharsets.UTF_8.name()
            );
            helper.setFrom(fromEmail, senderName);
            helper.setTo(event.toEmail());
            helper.setSubject(event.subject());
            helper.setText(htmlBody, true);

            mailSender.send(mimeMessage);
            log.info("Письмо успешно отправлено на {} (шаблон: {})", event.toEmail(), event.templateName());
        } catch (Exception ex) {
            log.error("Ошибка при отправке письма на {}: {}", event.toEmail(), ex.getMessage(), ex);
            throw new IllegalStateException("Не удалось отправить письмо на " + event.toEmail(), ex);
        }
    }
}
