package com.helpdesk.ai.autotriage;

import com.helpdesk.ai.AiDtos.AiTriageRequest;
import com.helpdesk.ai.AiTriageService;
import com.helpdesk.ai.autotriage.Messages.TriageRequestedMessage;
import com.helpdesk.repository.TicketRepository;
import com.helpdesk.service.TicketCreatedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.UUID;

/**
 * Announces new tickets on RabbitMQ, but only AFTER the ticket's transaction has committed:
 * a rollback never produces a message about a ticket that doesn't exist.
 *
 * Trade-off (worth knowing): if the broker is down at that moment the message is lost and the
 * ticket simply stays untriaged (a human, or POST /ai-triage, can still handle it). The fully
 * reliable version is the "transactional outbox" pattern: write the event to a DB table in the
 * same transaction and relay it to the broker separately.
 */
@Component
@ConditionalOnProperty(prefix = "helpdesk.auto-triage", name = "enabled", havingValue = "true")
public class TicketCreatedPublisher {

    private static final Logger log = LoggerFactory.getLogger(TicketCreatedPublisher.class);

    private final TicketRepository tickets;
    private final RabbitTemplate rabbit;
    private final TransactionTemplate readTx;
    private final Clock clock;

    public TicketCreatedPublisher(TicketRepository tickets, RabbitTemplate rabbit,
                                  PlatformTransactionManager txManager, Clock clock) {
        this.tickets = tickets;
        this.rabbit = rabbit;
        this.clock = clock;
        this.readTx = new TransactionTemplate(txManager);
        this.readTx.setReadOnly(true);
        // the original transaction has completed; use a fresh one for this read
        this.readTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTicketCreated(TicketCreatedEvent event) {
        try {
            AiTriageRequest request = readTx.execute(status -> tickets.findWithDetailsById(event.ticketId())
                    .map(AiTriageService::toRequest).orElse(null));
            if (request == null) {
                return;
            }
            var message = new TriageRequestedMessage(UUID.randomUUID().toString(), event.ticketId(),
                    clock.instant(), request);
            rabbit.convertAndSend(Messaging.EVENTS_EXCHANGE, Messaging.TICKET_CREATED, message, m -> {
                m.getMessageProperties().setMessageId(message.eventId());
                return m;
            });
            log.info("Published {} ticket={} eventId={}", Messaging.TICKET_CREATED, event.ticketId(), message.eventId());
        } catch (RuntimeException e) {
            // Never fail the HTTP request that created the ticket because of the broker.
            log.error("Could not publish {} for ticket {}: {}", Messaging.TICKET_CREATED, event.ticketId(), e.toString());
        }
    }
}
