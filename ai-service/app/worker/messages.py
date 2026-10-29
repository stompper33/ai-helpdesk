"""Message payloads. Mirror helpdesk-api's com.helpdesk.ai.autotriage.Messages.

They reuse the stage 2 HTTP contract (TriageRequest / TriageResponse), so a queued
ticket is triaged by exactly the same code as an HTTP request.
"""

from __future__ import annotations

from datetime import datetime

from app.schemas import CamelModel, TriageRequest, TriageResponse


class TriageRequestedMessage(CamelModel):
    event_id: str
    ticket_id: int
    occurred_at: datetime
    request: TriageRequest


class TriageCompletedMessage(CamelModel):
    event_id: str
    causation_id: str  # the request's event_id, so the two can be correlated in logs
    ticket_id: int
    result: TriageResponse
