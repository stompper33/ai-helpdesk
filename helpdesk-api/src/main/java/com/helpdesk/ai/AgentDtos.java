package com.helpdesk.ai;

import com.helpdesk.ai.AiDtos.Usage;
import com.helpdesk.web.dto.Responses.TicketDetailResponse;

import java.util.List;
import java.util.Map;

/** Stage 3 contract with ai-service. Mirrors ai-service/app/agent/schemas.py. */
public final class AgentDtos {

    private AgentDtos() {
    }

    /** Only the id: the Python agent fetches everything else itself through our REST API. */
    public record AgentDraftRequest(Long ticketId) {
    }

    public record AgentDraftResult(Long ticketId, String customerReply, String internalNote,
                                   List<String> suggestedActions, double confidence, int steps,
                                   List<ToolCallTrace> toolCalls, String provider, String model,
                                   long latencyMs, Usage usage) {
    }

    public record ToolCallTrace(String name, Map<String, Object> arguments, boolean ok, long durationMs) {
    }

    /** Returned by POST /api/tickets/{id}/ai-draft. */
    public record AiDraftResponse(Long ticketId, AgentDraftResult draft, TicketDetailResponse ticket) {
    }
}
