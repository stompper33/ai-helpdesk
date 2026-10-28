"""API contract between helpdesk-api (Java) and ai-service (Python).

JSON uses camelCase so it maps straight onto Java records. Enum values match
the Java enums exactly (TicketCategory, TicketPriority, CustomerTier).
"""

from __future__ import annotations

from enum import Enum

from pydantic import BaseModel, ConfigDict, Field
from pydantic.alias_generators import to_camel


class Category(str, Enum):
    BILLING = "BILLING"
    SHIPPING = "SHIPPING"
    RETURNS = "RETURNS"
    TECHNICAL = "TECHNICAL"
    ACCOUNT = "ACCOUNT"
    OTHER = "OTHER"


class Priority(str, Enum):
    LOW = "LOW"
    MEDIUM = "MEDIUM"
    HIGH = "HIGH"
    URGENT = "URGENT"


class Sentiment(str, Enum):
    POSITIVE = "POSITIVE"
    NEUTRAL = "NEUTRAL"
    NEGATIVE = "NEGATIVE"
    ANGRY = "ANGRY"


class CustomerTier(str, Enum):
    STANDARD = "STANDARD"
    PREMIUM = "PREMIUM"


class CamelModel(BaseModel):
    """Accepts and emits camelCase JSON, while Python code uses snake_case."""

    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)


# ---- request ---------------------------------------------------------------


class OrderContext(CamelModel):
    order_number: str
    product_name: str
    status: str
    amount: float | None = None


class TriageRequest(CamelModel):
    ticket_id: int | None = None
    subject: str = Field(min_length=1, max_length=200)
    description: str = Field(min_length=1, max_length=4000)
    customer_tier: CustomerTier = CustomerTier.STANDARD
    current_priority: Priority | None = None
    order: OrderContext | None = None


# ---- what the LLM must return ------------------------------------------------


class TriageDecision(BaseModel):
    """The structured output schema sent to the model.

    Kept deliberately simple (no defaults, no numeric constraints) so it is
    accepted by OpenAI's strict structured-output mode. We validate/clamp after.
    """

    category: Category = Field(description="The single best-fitting ticket category")
    priority: Priority = Field(description="Recommended priority per the guidelines")
    sentiment: Sentiment = Field(description="The customer's emotional tone")
    summary: str = Field(description="One or two sentences a support agent can read in five seconds")
    reasoning: str = Field(description="Briefly, why this category and priority were chosen")
    confidence: float = Field(description="Confidence in the category and priority, from 0.0 to 1.0")


# ---- response --------------------------------------------------------------


class Usage(CamelModel):
    input_tokens: int
    output_tokens: int


class TriageResponse(CamelModel):
    category: Category
    priority: Priority
    sentiment: Sentiment
    summary: str
    reasoning: str
    confidence: float
    provider: str
    model: str
    latency_ms: int
    usage: Usage | None = None


class HealthResponse(CamelModel):
    status: str
    provider: str
    model: str
