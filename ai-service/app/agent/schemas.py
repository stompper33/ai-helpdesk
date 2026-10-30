"""API contract for the reply-drafting agent (stage 3)."""

from __future__ import annotations

from enum import Enum
from typing import Any

from pydantic import BaseModel, Field

from app.schemas import CamelModel, Usage


class SuggestedAction(str, Enum):
    """Actions the agent may *suggest*. It can't perform any of them: a human decides."""

    REFUND = "REFUND"
    REPLACEMENT = "REPLACEMENT"
    ESCALATE = "ESCALATE"
    REQUEST_MORE_INFO = "REQUEST_MORE_INFO"
    NO_ACTION = "NO_ACTION"


# ---- what the model must pass to submit_reply ----------------------------------


class DraftSubmission(BaseModel):
    customer_reply: str = Field(min_length=1, max_length=2500)
    internal_note: str = Field(max_length=1000)
    suggested_actions: list[SuggestedAction]
    confidence: float
    sources: list[str]  # stage 4: knowledge base chunk ids the reply relies on


# ---- HTTP request / response ---------------------------------------------------


class DraftReplyRequest(CamelModel):
    ticket_id: int


class ToolCallTrace(CamelModel):
    name: str
    arguments: dict[str, Any]
    ok: bool
    duration_ms: int


class DraftReplyResponse(CamelModel):
    ticket_id: int
    customer_reply: str
    internal_note: str
    suggested_actions: list[SuggestedAction]
    confidence: float
    sources: list[str]  # only ids the agent actually retrieved (unverified citations are dropped)
    steps: int
    tool_calls: list[ToolCallTrace]
    provider: str
    model: str
    latency_ms: int
    usage: Usage | None = None
