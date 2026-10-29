package com.helpdesk.ai.autotriage;

/**
 * RabbitMQ names shared with ai-service (ai-service/app/worker/topology.py must match).
 *
 * <pre>
 *  helpdesk-api --ticket.created--> [helpdesk.events] --> ai.triage.requests       --> ai-service worker
 *  ai-service   --ticket.triaged--> [helpdesk.events] --> helpdesk.triage.results  --> helpdesk-api listener
 *
 *  A message that keeps failing is dead-lettered via [helpdesk.dlx] to "&lt;queue&gt;.dlq"
 *  instead of blocking the queue or being lost.
 * </pre>
 *
 * Queue arguments are declared identically by both services; RabbitMQ rejects a
 * re-declaration with different arguments, so change them in both places together.
 */
public final class Messaging {

    private Messaging() {
    }

    public static final String EVENTS_EXCHANGE = "helpdesk.events";      // topic
    public static final String DEAD_LETTER_EXCHANGE = "helpdesk.dlx";    // direct

    public static final String TICKET_CREATED = "ticket.created";
    public static final String TICKET_TRIAGED = "ticket.triaged";

    public static final String TRIAGE_REQUESTS = "ai.triage.requests";            // consumed by ai-service
    public static final String TRIAGE_REQUESTS_DLQ = TRIAGE_REQUESTS + ".dlq";
    public static final String TRIAGE_RESULTS = "helpdesk.triage.results";        // consumed by helpdesk-api
    public static final String TRIAGE_RESULTS_DLQ = TRIAGE_RESULTS + ".dlq";
}
