package com.helpdesk.ai;

import com.helpdesk.actions.ProposedActionDtos.ProposedActionResponse;
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

    /** sources (stage 4) = knowledge base passages the reply relies on, verified by ai-service. */
    public record AgentDraftResult(Long ticketId, String customerReply, String internalNote,
                                   List<String> suggestedActions, double confidence, int steps,
                                   List<ToolCallTrace> toolCalls, String provider, String model,
                                   long latencyMs, Usage usage, List<String> sources) {

        /** Pre-stage-4 shape (no sources), kept so existing callers and tests still compile. */
        public AgentDraftResult(Long ticketId, String customerReply, String internalNote,
                                List<String> suggestedActions, double confidence, int steps,
                                List<ToolCallTrace> toolCalls, String provider, String model,
                                long latencyMs, Usage usage) {
            this(ticketId, customerReply, internalNote, suggestedActions, confidence, steps, toolCalls, provider,
                    model, latencyMs, usage, List.of());
        }
    }

    public record ToolCallTrace(String name, Map<String, Object> arguments, boolean ok, long durationMs) {
    }

    /** Returned by POST /api/tickets/{id}/ai-draft. proposedActions = the PENDING proposals created (stage 5c). */
    public record AiDraftResponse(Long ticketId, AgentDraftResult draft, List<ProposedActionResponse> proposedActions,
                                  TicketDetailResponse ticket) {
    }
}
