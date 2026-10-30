package com.helpdesk.actions;

import com.helpdesk.domain.ProposedAction;
import com.helpdesk.domain.ProposedAction.Status;
import com.helpdesk.domain.ProposedAction.Type;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public final class ProposedActionDtos {

    private ProposedActionDtos() {
    }

    /** Who is deciding. There are no user logins yet, so the support agent's id is passed explicitly. */
    public record DecisionRequest(@NotNull Long agentId, @Size(max = 1000) String note) {
    }

    public record ProposedActionResponse(Long id, Long ticketId, Type type, Status status, String proposedBy,
                                         String reason, Instant createdAt, Long decidedById, String decidedByName,
                                         Instant decidedAt, String decisionNote, String executionResult) {
        public static ProposedActionResponse from(ProposedAction a) {
            var agent = a.getDecidedBy();
            return new ProposedActionResponse(a.getId(), a.getTicket().getId(), a.getType(), a.getStatus(),
                    a.getProposedBy(), a.getReason(), a.getCreatedAt(),
                    agent == null ? null : agent.getId(), agent == null ? null : agent.getName(),
                    a.getDecidedAt(), a.getDecisionNote(), a.getExecutionResult());
        }
    }
}
