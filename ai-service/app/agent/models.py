"""The "brain" behind the agent loop, behind a small interface.

A session is one conversation with the model: send the ticket, get tool calls back,
send tool results, repeat. Two implementations:

- OpenAIAgentModel: OpenAI Responses API with function calling. The conversation state
  is kept server-side via previous_response_id, so we only send the new tool results.
- ScriptedAgentModel: deterministic and offline. It calls the tools in a fixed order and
  fills a template. Used when no API key is set and in tests, so the real loop, tools
  and Java callbacks can all be exercised for free.
"""

from __future__ import annotations

import json
from dataclasses import dataclass
from typing import Any, Protocol

from app.agent.schemas import PolicyTopic, SuggestedAction
from app.agent.tools import SUBMIT_REPLY, TicketContext, ToolSpec
from app.schemas import Category, Usage


class AgentError(Exception):
    """The model failed, or the run could not finish."""


@dataclass(frozen=True)
class ToolCall:
    call_id: str
    name: str
    arguments: dict[str, Any]


@dataclass(frozen=True)
class ToolResult:
    call_id: str
    output: Any


@dataclass(frozen=True)
class ModelTurn:
    tool_calls: list[ToolCall]
    text: str | None = None
    usage: Usage | None = None


class AgentSession(Protocol):
    async def send(self, message: str) -> ModelTurn: ...

    async def send_tool_results(self, results: list[ToolResult]) -> ModelTurn: ...


class AgentModel(Protocol):
    name: str
    model: str

    def new_session(self, instructions: str, tools: list[ToolSpec], ctx: TicketContext) -> AgentSession: ...


# ---- OpenAI ----------------------------------------------------------------------


class OpenAIAgentModel:
    name = "openai"

    def __init__(self, api_key: str, model: str, timeout_seconds: float = 30.0):
        from openai import AsyncOpenAI

        self.model = model
        self._client = AsyncOpenAI(api_key=api_key, timeout=timeout_seconds, max_retries=2)

    def new_session(self, instructions: str, tools: list[ToolSpec], ctx: TicketContext) -> AgentSession:
        return _OpenAISession(self._client, self.model, instructions, tools)


class _OpenAISession:
    def __init__(self, client: Any, model: str, instructions: str, tools: list[ToolSpec]):
        self._client = client
        self._model = model
        self._instructions = instructions
        self._tools = [{"type": "function", "name": t.name, "description": t.description,
                        "parameters": t.parameters, "strict": True} for t in tools]
        self._previous_response_id: str | None = None

    async def send(self, message: str) -> ModelTurn:
        return await self._call(message)

    async def send_tool_results(self, results: list[ToolResult]) -> ModelTurn:
        return await self._call([
            {"type": "function_call_output", "call_id": r.call_id, "output": json.dumps(r.output, default=str)}
            for r in results
        ])

    async def _call(self, input_: Any) -> ModelTurn:
        import openai

        kwargs: dict[str, Any] = {
            "model": self._model,
            "instructions": self._instructions,  # not carried over by previous_response_id, so resend
            "input": input_,
            "tools": self._tools,
            "tool_choice": "required",  # every turn must be a tool call; the run ends via submit_reply
        }
        if self._previous_response_id:
            kwargs["previous_response_id"] = self._previous_response_id
        try:
            response = await self._client.responses.create(**kwargs)
        except openai.APIError as exc:
            raise AgentError(f"OpenAI request failed: {exc}") from exc
        self._previous_response_id = response.id

        calls = []
        for item in response.output:
            if getattr(item, "type", None) == "function_call":
                try:
                    args = json.loads(item.arguments or "{}")
                except json.JSONDecodeError:
                    args = {"_unparseable_arguments": item.arguments}
                calls.append(ToolCall(call_id=item.call_id, name=item.name, arguments=args))

        usage = None
        if response.usage is not None:
            usage = Usage(input_tokens=response.usage.input_tokens, output_tokens=response.usage.output_tokens)
        return ModelTurn(tool_calls=calls, text=getattr(response, "output_text", None) or None, usage=usage)


# ---- Offline scripted model ---------------------------------------------------------

_TOPIC_FOR_CATEGORY = {
    Category.RETURNS: PolicyTopic.RETURNS,
    Category.BILLING: PolicyTopic.REFUNDS,
    Category.SHIPPING: PolicyTopic.SHIPPING,
    Category.ACCOUNT: PolicyTopic.ACCOUNT_SECURITY,
    Category.TECHNICAL: PolicyTopic.TECHNICAL_SUPPORT,
    Category.OTHER: PolicyTopic.GENERAL,
}

_ACTION_FOR_TOPIC = {
    PolicyTopic.RETURNS: SuggestedAction.REPLACEMENT,
    PolicyTopic.REFUNDS: SuggestedAction.REFUND,
    PolicyTopic.SHIPPING: SuggestedAction.REQUEST_MORE_INFO,
    PolicyTopic.ACCOUNT_SECURITY: SuggestedAction.ESCALATE,
}


class ScriptedAgentModel:
    name = "rules"
    model = "scripted-agent-v1"

    def new_session(self, instructions: str, tools: list[ToolSpec], ctx: TicketContext) -> AgentSession:
        return _ScriptedSession(ctx)


class _ScriptedSession:
    def __init__(self, ctx: TicketContext):
        self._ctx = ctx
        self._topic = self._pick_topic(ctx)

    async def send(self, message: str) -> ModelTurn:
        calls = [
            ToolCall("c1", "get_customer_orders", {}),
            ToolCall("c2", "get_ticket_history", {}),
            ToolCall("c3", "get_support_policy", {"topic": self._topic.value}),
        ]
        return ModelTurn(tool_calls=calls)

    async def send_tool_results(self, results: list[ToolResult]) -> ModelTurn:
        outputs = {r.call_id: r.output for r in results}
        policy = outputs.get("c3", {}).get("policy", "") if isinstance(outputs.get("c3"), dict) else ""
        first_policy_sentence = policy.split("\n")[0].strip() if policy else ""
        orders = outputs.get("c1") if isinstance(outputs.get("c1"), list) else []
        history = outputs.get("c2") if isinstance(outputs.get("c2"), list) else []

        first_name = self._ctx.customer_name.split()[0]
        reply = (f"Hi {first_name},\n\nThanks for contacting us about \"{self._ctx.subject}\". "
                 f"{first_policy_sentence}\n\nA member of our team will review your case and follow up "
                 f"with next steps shortly.\n\nCustomer Support Team")
        note = (f"Scripted draft (no LLM). Policy topic: {self._topic.value}. "
                f"Customer has {len(orders)} order(s) and {len(history)} other ticket(s).")
        action = _ACTION_FOR_TOPIC.get(self._topic, SuggestedAction.NO_ACTION)
        submit = ToolCall("c4", SUBMIT_REPLY, {
            "customer_reply": reply, "internal_note": note,
            "suggested_actions": [action.value], "confidence": 0.4,
        })
        return ModelTurn(tool_calls=[submit])

    @staticmethod
    def _pick_topic(ctx: TicketContext) -> PolicyTopic:
        if ctx.category:
            return _TOPIC_FOR_CATEGORY.get(Category(ctx.category), PolicyTopic.GENERAL)
        from app.schemas import TriageRequest
        from app.triage.classifiers import RuleBasedClassifier

        guess = RuleBasedClassifier().decide(TriageRequest(subject=ctx.subject, description=ctx.description))
        return _TOPIC_FOR_CATEGORY[guess.category]
