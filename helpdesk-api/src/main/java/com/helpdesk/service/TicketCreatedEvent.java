package com.helpdesk.service;

/**
 * Spring application event, published by {@link TicketService#create} inside the transaction.
 * Listeners that talk to the outside world (stage 5b: RabbitMQ) should use
 * {@code @TransactionalEventListener(phase = AFTER_COMMIT)}, so nothing is announced for a
 * ticket whose transaction rolled back.
 */
public record TicketCreatedEvent(Long ticketId) {
}
