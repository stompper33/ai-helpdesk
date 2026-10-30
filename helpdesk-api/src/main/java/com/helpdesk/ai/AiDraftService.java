package com.helpdesk.ai;

import com.helpdesk.actions.ProposedActionDtos.ProposedActionResponse;
import com.helpdesk.actions.ProposedActionService;
import com.helpdesk.ai.AgentDtos.AgentDraftResult;
import com.helpdesk.ai.AgentDtos.AiDraftResponse;
import com.helpdesk.ai.AgentDtos.ToolCallTrace;
import com.helpdesk.domain.HelpDeskConstants.ErrorCodes;
import com.helpdesk.domain.Enums.AuthorType;
import com.helpdesk.domain.Ticket;
import com.helpdesk.domain.TicketStatus;
import com.helpdesk.exception.BusinessRuleException;
import com.helpdesk.exception.NotFoundException;
import com.helpdesk.repository.TicketRepository;
import com.helpdesk.web.dto.Responses.TicketDetailResponse;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Asks the Python agent to draft a reply and stores it as an INTERNAL AI comment.
 *
 * The flow goes both ways: Java -> Python (draft this ticket) -> Java (the agent's tools
 * call our read-only REST endpoints) -> Python -> Java (store the draft).
 *
 * Human in the loop: the draft is internal, so the customer never sees it. A support
 * agent reviews it, edits it if needed, and sends it as a normal AGENT comment.
 * Stage 5c: suggested actions (REFUND, REPLACEMENT...) become PENDING proposals that a
 * support agent approves or rejects via /api/actions; nothing is executed automatically.
 */
@Service
public class AiDraftService {

    static final String AI_AUTHOR = "AI reply assistant";
    private static final int MAX_COMMENT_LENGTH = 4000; // matches the TicketComment.body column

    private final TicketRepository ticketRepository;
    private final AiAgentClient client;
    private final ProposedActionService proposedActions;
    private final TransactionTemplate readTx;
    private final TransactionTemplate writeTx;
    private final Clock clock;

    public AiDraftService(TicketRepository tickets, AiAgentClient client, ProposedActionService proposedActions,
                          PlatformTransactionManager txManager, Clock clock) {
        this.ticketRepository = tickets;
        this.client = client;
        this.proposedActions = proposedActions;
        this.clock = clock;
        this.writeTx = new TransactionTemplate(txManager);
        this.readTx = new TransactionTemplate(txManager);
        this.readTx.setReadOnly(true);
    }

    public AiDraftResponse draft(Long ticketId) {
        // 1. fail fast before spending tokens (no transaction is held during step 2)
        readTx.executeWithoutResult(status -> requireNotClosed(load(ticketId)));

        // 2. the agent runs, calling back into our API for its tools
        AgentDraftResult draft = client.draftReply(ticketId);

        // 3. store it for human review
        return writeTx.execute(status -> {
            Ticket t = load(ticketId);
            requireNotClosed(t);
            Instant now = clock.instant();
            t.addComment(AuthorType.AI, AI_AUTHOR, describe(draft), true, now);
            List<ProposedActionResponse> proposed = proposedActions.propose(
                    t, draft.suggestedActions(), AI_AUTHOR, draft.internalNote(), now);
            ticketRepository.flush();
            return new AiDraftResponse(ticketId, draft, proposed, TicketDetailResponse.from(t, now));
        });
    }

    private String describe(AgentDraftResult d) {
        String tools = d.toolCalls() == null ? "none" : d.toolCalls().stream()
                .filter(c -> !"submit_reply".equals(c.name()))
                .map(AiDraftService::formatTool)
                .collect(Collectors.joining(", "));
        List<String> actions = d.suggestedActions() == null ? List.of() : d.suggestedActions();
        String text = String.format(Locale.ROOT, """
                        AI DRAFT REPLY - review and edit before sending (not visible to the customer)
                        ---
                        %s
                        ---
                        Suggested actions: %s (each one waits for human approval in /api/actions)
                        Agent notes: %s
                        Sources: %s
                        Tools used: %s
                        (confidence %.2f, %d steps, %s via %s, %d ms)""",
                d.customerReply(), actions.isEmpty() ? "none" : String.join(", ", actions),
                d.internalNote(),
                d.sources() == null || d.sources().isEmpty() ? "none" : String.join(", ", d.sources()),
                tools.isEmpty() ? "none" : tools,
                d.confidence(), d.steps(), d.model(), d.provider(), d.latencyMs());
        return text.length() <= MAX_COMMENT_LENGTH ? text : text.substring(0, MAX_COMMENT_LENGTH - 3) + "...";
    }

    private static String formatTool(ToolCallTrace c) {
        String args = c.arguments() == null || c.arguments().isEmpty() ? ""
                : c.arguments().values().stream().map(String::valueOf).collect(Collectors.joining(", "));
        return c.name() + "(" + args + ")" + (c.ok() ? "" : " [failed]");
    }

    private Ticket load(Long id) {
        return ticketRepository.findWithDetailsById(id).orElseThrow(() -> new NotFoundException("Ticket", id));
    }

    private static void requireNotClosed(Ticket t) {
        if (t.getStatus() == TicketStatus.CLOSED) {
            throw new BusinessRuleException(ErrorCodes.TICKET_CLOSED, "Ticket " + t.getId() + " is closed");
        }
    }
}
