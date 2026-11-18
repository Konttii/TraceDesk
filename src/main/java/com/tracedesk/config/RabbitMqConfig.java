package com.tracedesk.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMqConfig {

    public static final String EMAIL_EXCHANGE = "tracedesk.email.exchange";
    public static final String EMAIL_QUEUE = "tracedesk.email.queue";
    public static final String EMAIL_ROUTING_KEY = "tracedesk.email.routing-key";

    private final String emailExchangeName;
    private final String emailQueueName;
    private final String emailRoutingKey;

    public RabbitMqConfig(
            @Value("${app.rabbitmq.email-exchange:" + EMAIL_EXCHANGE + "}") String emailExchangeName,
            @Value("${app.rabbitmq.email-queue:" + EMAIL_QUEUE + "}") String emailQueueName,
            @Value("${app.rabbitmq.email-routing-key:" + EMAIL_ROUTING_KEY + "}") String emailRoutingKey
    ) {
        this.emailExchangeName = emailExchangeName;
        this.emailQueueName = emailQueueName;
        this.emailRoutingKey = emailRoutingKey;
    }

    @Bean
    public DirectExchange emailExchange() {
        return new DirectExchange(emailExchangeName, true, false);
    }

    @Bean
    public Queue emailQueue() {
        return QueueBuilder.durable(emailQueueName).build();
    }

    @Bean
    public Binding emailBinding(Queue emailQueue, DirectExchange emailExchange) {
        return BindingBuilder.bind(emailQueue).to(emailExchange).with(emailRoutingKey);
    }

    @Bean
    @SuppressWarnings("removal")
    public MessageConverter jackson2JsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
