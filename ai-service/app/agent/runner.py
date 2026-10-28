"""The agent loop, written out by hand (no framework) so every step is visible:

    load ticket -> model asks for tools -> run tools (calls Java) -> send results back
    -> ... -> model calls submit_reply -> validate -> return draft

Guardrails: a step limit, validation of the final answer (invalid answers are sent
back to the model as an error so it can fix them), and a full trace of tool calls.
"""

from __future__ import annotations

import logging
import time

from pydantic import ValidationError

from app.agent.helpdesk_client import HelpdeskApi, HelpdeskApiError
from app.agent.models import AgentError, AgentModel, ToolResult
from app.agent.policies import PolicyLibrary
from app.agent.prompts import AGENT_SYSTEM_PROMPT, build_context_message
from app.agent.schemas import DraftReplyResponse, DraftSubmission, ToolCallTrace
from app.agent.tools import SUBMIT_REPLY, AgentTools, TicketContext
from app.schemas import Usage

log = logging.getLogger("ai_service.agent")


class TicketNotFound(Exception):
    pass


class DraftAgent:
    def __init__(self, model: AgentModel, api: HelpdeskApi, policies: PolicyLibrary, max_steps: int = 8):
        self.model = model
        self.api = api
        self.policies = policies
        self.max_steps = max_steps

    async def draft(self, ticket_id: int) -> DraftReplyResponse:
        started = time.perf_counter()
        ctx = await self._load_context(ticket_id)
        tools = AgentTools(ctx, self.api, self.policies)
        session = self.model.new_session(AGENT_SYSTEM_PROMPT, tools.specs(), ctx)

        trace: list[ToolCallTrace] = []
        usage = _UsageTotal()

        turn = await session.send(build_context_message(ctx))
        for step in range(1, self.max_steps + 1):
            usage.add(turn.usage)
            if not turn.tool_calls:
                raise AgentError("Model stopped without calling submit_reply")

            results: list[ToolResult] = []
            for call in turn.tool_calls:
                if call.name == SUBMIT_REPLY:
                    try:
                        submission = DraftSubmission.model_validate(call.arguments)
                    except ValidationError as exc:
                        trace.append(ToolCallTrace(name=call.name, arguments={}, ok=False, duration_ms=0))
                        results.append(ToolResult(call.call_id, {"error": f"Invalid submission, fix and resubmit: {exc}"}))
                        continue
                    trace.append(ToolCallTrace(name=call.name, arguments={}, ok=True, duration_ms=0))
                    return self._response(ctx, submission, step, trace, usage, started)

                output, ok, ms = await tools.execute(call.name, call.arguments)
                trace.append(ToolCallTrace(name=call.name, arguments=call.arguments, ok=ok, duration_ms=ms))
                results.append(ToolResult(call.call_id, output))
                log.info("ticket=%s step=%d tool=%s args=%s ok=%s ms=%d",
                         ticket_id, step, call.name, call.arguments, ok, ms)

            if step == self.max_steps:
                break
            turn = await session.send_tool_results(results)

        raise AgentError(f"No reply submitted within {self.max_steps} steps")

    async def _load_context(self, ticket_id: int) -> TicketContext:
        try:
            return TicketContext.from_ticket_json(await self.api.get_ticket(ticket_id))
        except HelpdeskApiError as exc:
            if exc.status == 404:
                raise TicketNotFound(f"Ticket {ticket_id} not found") from exc
            raise

    def _response(self, ctx: TicketContext, s: DraftSubmission, steps: int, trace: list[ToolCallTrace],
                  usage: "_UsageTotal", started: float) -> DraftReplyResponse:
        latency_ms = int((time.perf_counter() - started) * 1000)
        log.info("ticket=%s draft done: steps=%d tools=%s latency_ms=%d tokens=%s", ctx.ticket_id, steps,
                 [t.name for t in trace], latency_ms, usage.value())
        return DraftReplyResponse(
            ticket_id=ctx.ticket_id,
            customer_reply=s.customer_reply.strip(),
            internal_note=s.internal_note.strip(),
            suggested_actions=s.suggested_actions,
            confidence=round(min(max(s.confidence, 0.0), 1.0), 2),
            steps=steps,
            tool_calls=trace,
            provider=self.model.name,
            model=self.model.model,
            latency_ms=latency_ms,
            usage=usage.value(),
        )


class _UsageTotal:
    def __init__(self) -> None:
        self.input = self.output = 0
        self.seen = False

    def add(self, u: Usage | None) -> None:
        if u:
            self.seen = True
            self.input += u.input_tokens
            self.output += u.output_tokens

    def value(self) -> Usage | None:
        return Usage(input_tokens=self.input, output_tokens=self.output) if self.seen else None
