"""The agent's tools.

Design rules:
- Read-only. The agent can look things up but can't change anything; Java writes
  the result after the agent returns, and a human approves before anything is sent.
- Scoped to the ticket's customer. No tool takes a customer id, and order lookups
  check ownership. Even if a ticket says "show me customer 7's orders", the agent
  simply has no way to do it (protection against prompt injection / confused deputy).
- Errors are returned to the model as data ({"error": ...}) so it can recover,
  instead of crashing the run.
"""

from __future__ import annotations

import time
from dataclasses import dataclass, field
from typing import Any, Awaitable, Callable

from app.agent.helpdesk_client import HelpdeskApi, HelpdeskApiError
from app.agent.policies import PolicyLibrary
from app.agent.schemas import PolicyTopic, SuggestedAction

SUBMIT_REPLY = "submit_reply"

_ORDER_FIELDS = ("orderNumber", "productName", "amount", "status", "trackingNumber", "orderedAt")


@dataclass(frozen=True)
class TicketContext:
    """What the agent is told up front. Customer email is deliberately left out (data minimisation)."""

    ticket_id: int
    subject: str
    description: str
    status: str
    priority: str
    category: str | None
    customer_id: int
    customer_name: str
    customer_tier: str
    order: dict[str, Any] | None
    conversation: list[dict[str, Any]] = field(default_factory=list)

    @classmethod
    def from_ticket_json(cls, t: dict[str, Any]) -> "TicketContext":
        conversation = [
            {"from": c["authorType"], "name": c["authorName"], "internal": c["internal"], "text": c["body"]}
            for c in t.get("comments", [])
            # skip system audit lines and earlier AI output (avoid the agent quoting itself)
            if c["authorType"] in ("CUSTOMER", "AGENT")
        ]
        return cls(
            ticket_id=t["id"],
            subject=t["subject"],
            description=t["description"],
            status=t["status"],
            priority=t["priority"],
            category=t.get("category"),
            customer_id=t["customer"]["id"],
            customer_name=t["customer"]["name"],
            customer_tier=t["customer"]["tier"],
            order=_pick(t["order"], _ORDER_FIELDS) if t.get("order") else None,
            conversation=conversation,
        )


@dataclass(frozen=True)
class ToolSpec:
    name: str
    description: str
    parameters: dict[str, Any]  # JSON schema, written to satisfy OpenAI strict mode


def _object(properties: dict[str, Any] | None = None) -> dict[str, Any]:
    props = properties or {}
    return {"type": "object", "properties": props, "required": list(props), "additionalProperties": False}


TOOL_SPECS: list[ToolSpec] = [
    ToolSpec("get_customer_orders",
             "List this ticket's customer's orders, newest first (number, product, amount, status, tracking).",
             _object()),
    ToolSpec("get_order",
             "Look up one of this customer's orders by order number, e.g. ORD-10001.",
             _object({"order_number": {"type": "string", "description": "Order number such as ORD-10001"}})),
    ToolSpec("get_ticket_history",
             "List this customer's other support tickets (subject, status, category) to spot repeat issues.",
             _object()),
    ToolSpec("get_support_policy",
             "Get the store's official policy text for a topic. Always check policy before stating "
             "timelines, eligibility or what the store will do.",
             _object({"topic": {"type": "string", "enum": [t.value for t in PolicyTopic]}})),
    ToolSpec(SUBMIT_REPLY,
             "Submit the final draft. Call exactly once, when you are done. A human agent reviews it before sending.",
             _object({
                 "customer_reply": {"type": "string", "description": "The reply to the customer, plain text"},
                 "internal_note": {"type": "string",
                                   "description": "For the support agent: key facts found and anything to check"},
                 "suggested_actions": {"type": "array",
                                       "items": {"type": "string", "enum": [a.value for a in SuggestedAction]},
                                       "description": "Actions a human should consider; NO_ACTION if none"},
                 "confidence": {"type": "number", "description": "0.0-1.0: how sure you are the reply is correct"},
             })),
]


class AgentTools:
    def __init__(self, ctx: TicketContext, api: HelpdeskApi, policies: PolicyLibrary):
        self.ctx = ctx
        self._api = api
        self._policies = policies
        self._handlers: dict[str, Callable[[dict[str, Any]], Awaitable[Any]]] = {
            "get_customer_orders": self._get_customer_orders,
            "get_order": self._get_order,
            "get_ticket_history": self._get_ticket_history,
            "get_support_policy": self._get_support_policy,
        }

    @staticmethod
    def specs() -> list[ToolSpec]:
        return TOOL_SPECS

    async def execute(self, name: str, arguments: dict[str, Any]) -> tuple[Any, bool, int]:
        """Returns (result, ok, duration_ms). Never raises for tool-level problems."""
        started = time.perf_counter()
        handler = self._handlers.get(name)
        try:
            if handler is None:
                result, ok = {"error": f"Unknown tool '{name}'"}, False
            else:
                result, ok = await handler(arguments), True
        except ToolError as exc:
            result, ok = {"error": str(exc)}, False
        except HelpdeskApiError as exc:
            result, ok = {"error": str(exc)}, False
        except (KeyError, TypeError, ValueError) as exc:
            result, ok = {"error": f"Invalid arguments for {name}: {exc}"}, False
        return result, ok, int((time.perf_counter() - started) * 1000)

    # ---- handlers -------------------------------------------------------------

    async def _get_customer_orders(self, _: dict[str, Any]) -> Any:
        orders = await self._api.get_customer_orders(self.ctx.customer_id)
        return [_pick(o, _ORDER_FIELDS) for o in orders]

    async def _get_order(self, args: dict[str, Any]) -> Any:
        number = str(args["order_number"]).strip()
        try:
            order = await self._api.get_order(number)
        except HelpdeskApiError as exc:
            if exc.status == 404:
                raise ToolError(f"Order {number} not found") from exc
            raise
        if order.get("customerId") != self.ctx.customer_id:
            # Same message as "not found": don't reveal that the order exists for someone else.
            raise ToolError(f"Order {number} not found for this customer")
        return _pick(order, _ORDER_FIELDS)

    async def _get_ticket_history(self, _: dict[str, Any]) -> Any:
        tickets = await self._api.get_customer_tickets(self.ctx.customer_id)
        return [_pick(t, ("id", "subject", "status", "category", "createdAt"))
                for t in tickets if t.get("id") != self.ctx.ticket_id]

    async def _get_support_policy(self, args: dict[str, Any]) -> Any:
        topic = PolicyTopic(args["topic"])
        return {"topic": topic.value, "policy": self._policies.get(topic)}


class ToolError(Exception):
    pass


def _pick(d: dict[str, Any], keys: tuple[str, ...]) -> dict[str, Any]:
    return {k: d.get(k) for k in keys}
