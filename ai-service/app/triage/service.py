"""Turns a classifier's raw decision into the API response, with timing, clamping and logging."""

from __future__ import annotations

import logging
import time

from app.schemas import TriageRequest, TriageResponse
from app.triage.classifiers import TicketClassifier

log = logging.getLogger("ai_service.triage")


class TriageService:
    def __init__(self, classifier: TicketClassifier):
        self.classifier = classifier

    async def triage(self, req: TriageRequest) -> TriageResponse:
        started = time.perf_counter()
        result = await self.classifier.classify(req)
        latency_ms = int((time.perf_counter() - started) * 1000)

        d = result.decision
        confidence = round(min(max(d.confidence, 0.0), 1.0), 2)  # the schema can't enforce the range

        log.info(
            "triage ticket=%s provider=%s model=%s category=%s priority=%s confidence=%.2f latency_ms=%d tokens=%s",
            req.ticket_id, self.classifier.name, result.model, d.category.value, d.priority.value,
            confidence, latency_ms,
            f"{result.usage.input_tokens}+{result.usage.output_tokens}" if result.usage else "n/a",
        )

        return TriageResponse(
            category=d.category,
            priority=d.priority,
            sentiment=d.sentiment,
            summary=d.summary.strip(),
            reasoning=d.reasoning.strip(),
            confidence=confidence,
            provider=self.classifier.name,
            model=result.model,
            latency_ms=latency_ms,
            usage=result.usage,
        )
