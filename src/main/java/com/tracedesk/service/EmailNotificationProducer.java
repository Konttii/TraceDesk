package com.tracedesk.service;

import com.tracedesk.config.RabbitMqConfig;
import com.tracedesk.web.dto.EmailMessageEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Service
public class EmailNotificationProducer {

    private static final Logger log = LoggerFactory.getLogger(EmailNotificationProducer.class);

    public static final String TEMPLATE_OTP_VERIFICATION = "email/otp-verification";
    public static final String TEMPLATE_ACCOUNT_DECISION = "email/account-decision";

    private final RabbitTemplate rabbitTemplate;

    public EmailNotificationProducer(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    public void publish(EmailMessageEvent event) {
        log.info("Публикация события отправки письма в RabbitMQ: to={}, template={}", event.toEmail(), event.templateName());
        rabbitTemplate.convertAndSend(
                RabbitMqConfig.EMAIL_EXCHANGE,
                RabbitMqConfig.EMAIL_ROUTING_KEY,
                event
        );
    }

    public void sendOtpVerificationEmail(String toEmail, String firstName, String otpCode) {
        Map<String, Object> vars = new HashMap<>();
        vars.put("firstName", firstName);
        vars.put("otpCode", otpCode);
        vars.put("ttlMinutes", 10);

        publish(new EmailMessageEvent(
                toEmail,
                "TraceDesk — Код подтверждения регистрации",
                TEMPLATE_OTP_VERIFICATION,
                vars
        ));
    }

    public void sendAccountDecisionEmail(String toEmail, String firstName, String iin, String decision) {
        boolean approved = "APPROVE".equalsIgnoreCase(decision) || "ACTIVE".equalsIgnoreCase(decision);
        Map<String, Object> vars = new HashMap<>();
        vars.put("firstName", firstName);
        vars.put("iin", iin);
        vars.put("decision", approved ? "APPROVE" : "REJECT");
        vars.put("approved", approved);

        String subject = approved
                ? "TraceDesk — Ваша учётная запись одобрена"
                : "TraceDesk — Решение по заявке на регистрацию";

        publish(new EmailMessageEvent(
                toEmail,
                subject,
                TEMPLATE_ACCOUNT_DECISION,
                vars
        ));
    }
}
