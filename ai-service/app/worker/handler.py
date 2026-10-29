"""What to do with one ticket.created message. Pure logic: no RabbitMQ imports, fully unit-tested.

    valid message + triage OK        -> ACK, publish a ticket.triaged reply
    valid message + triage failed    -> RETRY (with backoff), until max attempts, then DEAD_LETTER
    unreadable / invalid message     -> DEAD_LETTER at once (a "poison message": retrying won't help)
"""

from __future__ import annotations

import logging
import uuid
from dataclasses import dataclass
from enum import Enum
from typing import Any

from pydantic import ValidationError

from app.triage.service import TriageService
from app.worker.messages import TriageCompletedMessage, TriageRequestedMessage

log = logging.getLogger("ai_service.worker")


class Decision(str, Enum):
    ACK = "ACK"
    RETRY = "RETRY"
    DEAD_LETTER = "DEAD_LETTER"


@dataclass(frozen=True)
class HandleResult:
    decision: Decision
    reply: dict[str, Any] | None = None  # JSON body for ticket.triaged when decision is ACK
    reason: str = ""


async def handle_triage_request(body: bytes, attempt: int, max_attempts: int,
                                triage_service: TriageService) -> HandleResult:
    try:
        message = TriageRequestedMessage.model_validate_json(body)
    except ValidationError as exc:
        return HandleResult(Decision.DEAD_LETTER, reason=f"invalid message: {exc.error_count()} error(s)")

    try:
        result = await triage_service.triage(message.request)
    except Exception as exc:  # LLM down, timeout, rate limit... usually temporary
        if attempt < max_attempts:
            return HandleResult(Decision.RETRY, reason=f"attempt {attempt}/{max_attempts} failed: {exc}")
        return HandleResult(Decision.DEAD_LETTER, reason=f"gave up after {attempt} attempts: {exc}")

    reply = TriageCompletedMessage(
        event_id=str(uuid.uuid4()),
        causation_id=message.event_id,
        ticket_id=message.ticket_id,
        result=result,
    )
    return HandleResult(Decision.ACK, reply=reply.model_dump(mode="json", by_alias=True))


def retry_delay_seconds(attempt: int) -> float:
    """Exponential backoff: 2s, 4s, 8s ... capped at 30s."""
    return float(min(2 ** attempt, 30))
