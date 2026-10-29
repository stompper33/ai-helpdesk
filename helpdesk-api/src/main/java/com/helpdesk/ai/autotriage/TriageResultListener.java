package com.helpdesk.ai.autotriage;

import com.helpdesk.ai.autotriage.Messages.TriageCompletedMessage;
import com.helpdesk.exception.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Consumes triage results from ai-service.
 *
 * Failure handling:
 * - unexpected errors (e.g. database briefly down) are retried with backoff
 *   (spring.rabbitmq.listener.simple.retry), then dead-lettered to helpdesk.triage.results.dlq
 * - permanent problems (bad message, unknown ticket) throw AmqpRejectAndDontRequeueException, so
 *   they end up in the DLQ for inspection and are never requeued in an endless loop
 */
@Component
@ConditionalOnProperty(prefix = "helpdesk.auto-triage", name = "enabled", havingValue = "true")
public class TriageResultListener {

    private static final Logger log = LoggerFactory.getLogger(TriageResultListener.class);

    private final AutoTriageService autoTriage;

    public TriageResultListener(AutoTriageService autoTriage) {
        this.autoTriage = autoTriage;
    }

    @RabbitListener(queues = Messaging.TRIAGE_RESULTS)
    public void onTriageCompleted(TriageCompletedMessage message) {
        if (message == null || message.ticketId() == null || message.result() == null
                || message.result().category() == null || message.result().priority() == null) {
            throw new AmqpRejectAndDontRequeueException("Incomplete triage result message: " + message);
        }
        try {
            AutoTriageService.Result result = autoTriage.apply(message.ticketId(), message.result());
            log.info("Auto-triage ticket={} -> {} ({} / {}, confidence {}, causationId={})", message.ticketId(),
                    result, message.result().category(), message.result().priority(),
                    message.result().confidence(), message.causationId());
        } catch (NotFoundException e) {
            throw new AmqpRejectAndDontRequeueException("Ticket " + message.ticketId() + " not found", e);
        }
    }
}
