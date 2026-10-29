"""RabbitMQ names. Must match helpdesk-api's com.helpdesk.ai.autotriage.Messaging.

Both services declare the same exchanges and queues with the SAME arguments
(RabbitMQ refuses a re-declaration with different ones), so change them together.
"""

EVENTS_EXCHANGE = "helpdesk.events"       # topic
DEAD_LETTER_EXCHANGE = "helpdesk.dlx"     # direct

TICKET_CREATED = "ticket.created"
TICKET_TRIAGED = "ticket.triaged"

TRIAGE_REQUESTS = "ai.triage.requests"    # this worker consumes it
TRIAGE_REQUESTS_DLQ = TRIAGE_REQUESTS + ".dlq"

TRIAGE_REQUESTS_ARGUMENTS = {
    "x-dead-letter-exchange": DEAD_LETTER_EXCHANGE,
    "x-dead-letter-routing-key": TRIAGE_REQUESTS_DLQ,
}

ATTEMPTS_HEADER = "x-attempts"
