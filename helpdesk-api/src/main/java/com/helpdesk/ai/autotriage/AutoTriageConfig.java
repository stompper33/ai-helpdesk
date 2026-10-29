package com.helpdesk.ai.autotriage;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.*;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static com.helpdesk.ai.autotriage.Messaging.*;

/**
 * RabbitMQ setup, active only with helpdesk.auto-triage.enabled=true (the docker profile).
 * Spring's RabbitAdmin declares these on first connection; declaring is idempotent.
 */
@Configuration
@ConditionalOnProperty(prefix = "helpdesk.auto-triage", name = "enabled", havingValue = "true")
public class AutoTriageConfig {

    @Bean
    Declarables helpdeskMessagingTopology() {
        TopicExchange events = ExchangeBuilder.topicExchange(EVENTS_EXCHANGE).durable(true).build();
        DirectExchange dlx = ExchangeBuilder.directExchange(DEAD_LETTER_EXCHANGE).durable(true).build();

        Queue requests = QueueBuilder.durable(TRIAGE_REQUESTS)
                .deadLetterExchange(DEAD_LETTER_EXCHANGE).deadLetterRoutingKey(TRIAGE_REQUESTS_DLQ).build();
        Queue requestsDlq = QueueBuilder.durable(TRIAGE_REQUESTS_DLQ).build();
        Queue results = QueueBuilder.durable(TRIAGE_RESULTS)
                .deadLetterExchange(DEAD_LETTER_EXCHANGE).deadLetterRoutingKey(TRIAGE_RESULTS_DLQ).build();
        Queue resultsDlq = QueueBuilder.durable(TRIAGE_RESULTS_DLQ).build();

        // Java declares the request queue too, so ticket.created messages are kept even if
        // ai-service has never started yet (an exchange drops messages with no bound queue).
        return new Declarables(events, dlx, requests, requestsDlq, results, resultsDlq,
                BindingBuilder.bind(requests).to(events).with(TICKET_CREATED),
                BindingBuilder.bind(results).to(events).with(TICKET_TRIAGED),
                BindingBuilder.bind(requestsDlq).to(dlx).with(TRIAGE_REQUESTS_DLQ),
                BindingBuilder.bind(resultsDlq).to(dlx).with(TRIAGE_RESULTS_DLQ));
    }

    /** JSON with Spring Boot's ObjectMapper (ISO-8601 dates, unknown fields ignored). */
    @Bean
    MessageConverter amqpJsonMessageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }
}
