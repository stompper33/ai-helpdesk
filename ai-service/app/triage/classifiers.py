"""Ticket classifiers.

Everything that talks to a model sits behind the TicketClassifier protocol, so the
rest of the service doesn't care whether the answer came from OpenAI or from the
offline keyword rules (used for tests, demos without an API key, and as a baseline
for the eval script).
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from typing import Protocol

from app.schemas import Category, Priority, Sentiment, TriageDecision, TriageRequest, Usage
from app.triage.prompts import SYSTEM_PROMPT, build_user_message


class ClassifierError(Exception):
    """The model call failed or returned something unusable."""


@dataclass(frozen=True)
class Classification:
    decision: TriageDecision
    model: str
    usage: Usage | None = None


class TicketClassifier(Protocol):
    name: str
    model: str

    async def classify(self, req: TriageRequest) -> Classification: ...


# ---- OpenAI -------------------------------------------------------------------


class OpenAIClassifier:
    """Uses OpenAI structured outputs: the SDK sends TriageDecision as a JSON schema
    and parses the reply back into the pydantic model, so we never parse free text."""

    name = "openai"

    def __init__(self, api_key: str, model: str, timeout_seconds: float = 20.0):
        from openai import AsyncOpenAI  # imported lazily so the rules provider needs no SDK

        self.model = model
        self._client = AsyncOpenAI(api_key=api_key, timeout=timeout_seconds, max_retries=2)

    async def classify(self, req: TriageRequest) -> Classification:
        import openai

        try:
            response = await self._client.responses.parse(
                model=self.model,
                instructions=SYSTEM_PROMPT,
                input=build_user_message(req),
                text_format=TriageDecision,
            )
        except openai.APIError as exc:
            raise ClassifierError(f"OpenAI request failed: {exc}") from exc

        decision = response.output_parsed
        if decision is None:  # refusal or empty output
            raise ClassifierError("Model returned no parseable triage decision")

        usage = None
        if response.usage is not None:
            usage = Usage(input_tokens=response.usage.input_tokens, output_tokens=response.usage.output_tokens)
        return Classification(decision=decision, model=response.model or self.model, usage=usage)


# ---- Offline keyword rules -----------------------------------------------------

_CATEGORY_KEYWORDS: dict[Category, tuple[str, ...]] = {
    Category.BILLING: ("charge", "charged", "refund", "invoice", "payment", "billing", "card", "price"),
    Category.SHIPPING: ("tracking", "delivery", "shipped", "shipping", "arrive", "where is", "package", "courier"),
    Category.RETURNS: ("broken", "damaged", "defective", "warranty", "replacement", "return", "crackle", "faulty",
                       "doesn't work", "does not work", "stopped working", "wrong item"),
    Category.TECHNICAL: ("setup", "set up", "install", "firmware", "update", "bluetooth", "pair", "driver", "how do i",
                         "connect"),
    Category.ACCOUNT: ("password", "login", "log in", "sign in", "account", "email address", "2fa", "username"),
}

_ANGRY = ("unacceptable", "ridiculous", "furious", "worst", "scam", "!!!")
_NEGATIVE = ("frustrat", "disappoint", "annoy", "still not", "never", "upset", "broken", "problem")
_POSITIVE = ("thanks", "thank you", "great", "love", "appreciate")
_URGENT = ("urgent", "asap", "immediately", "hacked", "fraud", "unauthori", "stolen")
_TIME_SENSITIVE = ("tomorrow", "monday", "deadline", "today", "for work", "need it by")

_ORDER = [Priority.LOW, Priority.MEDIUM, Priority.HIGH, Priority.URGENT]


def _bump(p: Priority) -> Priority:
    return _ORDER[min(_ORDER.index(p) + 1, len(_ORDER) - 1)]


class RuleBasedClassifier:
    """Deterministic keyword classifier. Not smart, but free, instant and predictable."""

    name = "rules"
    model = "keyword-rules-v1"

    async def classify(self, req: TriageRequest) -> Classification:
        return Classification(decision=self.decide(req), model=self.model)

    def decide(self, req: TriageRequest) -> TriageDecision:
        text = f"{req.subject}\n{req.description}".lower()

        scores = {cat: sum(1 for kw in kws if kw in text) for cat, kws in _CATEGORY_KEYWORDS.items()}
        best = max(scores, key=lambda c: scores[c])
        category = best if scores[best] > 0 else Category.OTHER
        matched = scores[best]

        if any(w in text for w in _ANGRY):
            sentiment = Sentiment.ANGRY
        elif any(w in text for w in _NEGATIVE):
            sentiment = Sentiment.NEGATIVE
        elif any(w in text for w in _POSITIVE):
            sentiment = Sentiment.POSITIVE
        else:
            sentiment = Sentiment.NEUTRAL

        if any(w in text for w in _URGENT):
            priority = Priority.URGENT
        else:
            priority = req.current_priority or Priority.MEDIUM
            if any(w in text for w in _TIME_SENSITIVE):
                priority = _bump(priority)

        confidence = 0.3 if category == Category.OTHER else min(0.45 + 0.1 * matched, 0.75)

        return TriageDecision(
            category=category,
            priority=priority,
            sentiment=sentiment,
            summary=_first_sentence(req.description),
            reasoning=f"Keyword rules: {matched} {category.value.lower()} keyword(s) matched;",
            confidence=round(confidence, 2),
        )


def _first_sentence(text: str, limit: int = 200) -> str:
    sentence = re.split(r"(?<=[.!?])\s+", text.strip(), maxsplit=1)[0]
    return sentence if len(sentence) <= limit else sentence[: limit - 3].rstrip() + "..."
