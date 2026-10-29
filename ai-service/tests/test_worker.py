"""Auto-triage worker decisions (stage 5b). No RabbitMQ needed: the handler is pure logic."""

import asyncio
import json

from app.schemas import TriageRequest
from app.triage.classifiers import ClassifierError, RuleBasedClassifier
from app.triage.service import TriageService
from app.worker.handler import Decision, handle_triage_request, retry_delay_seconds

# Shaped exactly like helpdesk-api's TriageRequestedMessage (Jackson output)
JAVA_MESSAGE = {
    "eventId": "3f0c2b9e-5a1d-4c8e-9a43-1b2c3d4e5f60",
    "ticketId": 42,
    "occurredAt": "2026-10-28T20:15:30.123456Z",
    "request": {
        "ticketId": 42, "subject": "Where is my docking station?",
        "description": "Tracking hasn't updated in five days and I need it for work on Monday.",
        "customerTier": "PREMIUM", "currentPriority": "HIGH",
        "order": {"orderNumber": "ORD-10002", "productName": "USB-C docking station",
                  "status": "SHIPPED", "amount": 129.00},
    },
}


class FailingClassifier:
    name, model = "failing", "none"

    async def classify(self, req: TriageRequest):
        raise ClassifierError("OpenAI timed out")


def handle(body, attempt=1, max_attempts=3, classifier=None):
    service = TriageService(classifier or RuleBasedClassifier())
    return asyncio.run(handle_triage_request(body, attempt, max_attempts, service))


def test_valid_message_is_acked_with_a_reply_java_can_read():
    result = handle(json.dumps(JAVA_MESSAGE).encode())
    assert result.decision is Decision.ACK
    reply = result.reply
    # Keys must match Java's TriageCompletedMessage + AiTriageResult records
    assert sorted(reply) == ["causationId", "eventId", "result", "ticketId"]
    assert reply["causationId"] == JAVA_MESSAGE["eventId"] and reply["eventId"] != JAVA_MESSAGE["eventId"]
    assert reply["ticketId"] == 42
    assert sorted(reply["result"]) == sorted(["category", "priority", "sentiment", "summary", "reasoning",
                                              "confidence", "provider", "model", "latencyMs", "usage"])
    assert reply["result"]["category"] == "SHIPPING"
    json.dumps(reply)  # must be plain JSON (no datetime/enum objects)


def test_garbage_is_a_poison_message_and_goes_straight_to_the_dlq():
    for body in (b"{not json", b'{"eventId": "x"}', json.dumps({**JAVA_MESSAGE, "ticketId": "abc"}).encode()):
        result = handle(body)
        assert result.decision is Decision.DEAD_LETTER
        assert "invalid message" in result.reason


def test_unknown_enum_value_from_java_is_rejected_not_retried():
    bad = json.loads(json.dumps(JAVA_MESSAGE))
    bad["request"]["customerTier"] = "GOLD"
    assert handle(json.dumps(bad).encode()).decision is Decision.DEAD_LETTER


def test_ai_failure_is_retried_then_dead_lettered():
    body = json.dumps(JAVA_MESSAGE).encode()
    assert handle(body, attempt=1, classifier=FailingClassifier()).decision is Decision.RETRY
    assert handle(body, attempt=2, classifier=FailingClassifier()).decision is Decision.RETRY
    last = handle(body, attempt=3, classifier=FailingClassifier())
    assert last.decision is Decision.DEAD_LETTER
    assert "gave up after 3 attempts" in last.reason


def test_backoff_grows_and_is_capped():
    assert [retry_delay_seconds(a) for a in (1, 2, 3, 10)] == [2, 4, 8, 30]


def test_topology_matches_java_names():
    from app.worker import topology as t

    assert t.TRIAGE_REQUESTS_ARGUMENTS == {"x-dead-letter-exchange": "helpdesk.dlx",
                                           "x-dead-letter-routing-key": "ai.triage.requests.dlq"}
    assert (t.EVENTS_EXCHANGE, t.TICKET_CREATED, t.TICKET_TRIAGED) == \
           ("helpdesk.events", "ticket.created", "ticket.triaged")
