package com.helpdesk.ai.autotriage;

import com.helpdesk.ai.AiDtos.AiTriageRequest;
import com.helpdesk.ai.AiDtos.AiTriageResult;

import java.time.Instant;

/**
 * Message payloads (JSON). They reuse the stage 2 REST contract, so the AI side handles a
 * queued request exactly like an HTTP one. Mirrors ai-service/app/worker/messages.py.
 */
public final class Messages {

    private Messages() {
    }

    /** Published on ticket.created. eventId doubles as the AMQP message id. */
    public record TriageRequestedMessage(String eventId, Long ticketId, Instant occurredAt,
                                         AiTriageRequest request) {
    }

    /** Published by ai-service on ticket.triaged. causationId = the request's eventId (for tracing). */
    public record TriageCompletedMessage(String eventId, String causationId, Long ticketId,
                                         AiTriageResult result) {
    }
}
