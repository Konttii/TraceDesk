package com.tracedesk.service;

import com.tracedesk.config.RabbitMqConfig;
import com.tracedesk.web.dto.EmailMessageEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Service
public class EmailNotificationProducer {

    private static final Logger log = LoggerFactory.getLogger(EmailNotificationProducer.class);

    public static final String TEMPLATE_OTP_VERIFICATION = "email/otp-verification";
    public static final String TEMPLATE_ACCOUNT_DECISION = "email/account-decision";
    public static final String TEMPLATE_PASSWORD_RESET_OTP = "email/password-reset-otp";
    public static final String TEMPLATE_PASSWORD_CHANGED = "email/password-changed";

    private final RabbitTemplate rabbitTemplate;
    private final String emailExchange;
    private final String emailRoutingKey;

    public EmailNotificationProducer(
            RabbitTemplate rabbitTemplate,
            @Value("${app.rabbitmq.email-exchange:" + RabbitMqConfig.EMAIL_EXCHANGE + "}") String emailExchange,
            @Value("${app.rabbitmq.email-routing-key:" + RabbitMqConfig.EMAIL_ROUTING_KEY + "}") String emailRoutingKey
    ) {
        this.rabbitTemplate = rabbitTemplate;
        this.emailExchange = emailExchange;
        this.emailRoutingKey = emailRoutingKey;
    }

    public void publish(EmailMessageEvent event) {
        log.info("Публикация события отправки письма в RabbitMQ: to={}, template={}", event.toEmail(), event.templateName());
        rabbitTemplate.convertAndSend(
                emailExchange,
                emailRoutingKey,
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

    public void sendPasswordResetOtpEmail(String toEmail, String firstName, String otpCode) {
        Map<String, Object> vars = new HashMap<>();
        vars.put("firstName", firstName);
        vars.put("otpCode", otpCode);
        vars.put("ttlMinutes", 10);

        publish(new EmailMessageEvent(
                toEmail,
                "TraceDesk — Код восстановления пароля",
                TEMPLATE_PASSWORD_RESET_OTP,
                vars
        ));
    }

    public void sendPasswordChangedEmail(String toEmail, String firstName, String iin) {
        Map<String, Object> vars = new HashMap<>();
        vars.put("firstName", firstName);
        vars.put("iin", iin);

        publish(new EmailMessageEvent(
                toEmail,
                "TraceDesk — Пароль успешно изменен",
                TEMPLATE_PASSWORD_CHANGED,
                vars
        ));
    }
}
