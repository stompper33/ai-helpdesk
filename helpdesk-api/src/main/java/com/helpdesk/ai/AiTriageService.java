package com.helpdesk.ai;

import com.helpdesk.ai.AiDtos.*;
import com.helpdesk.domain.CustomerOrder;
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
import java.util.Locale;

/**
 * Orchestrates AI triage in three steps:
 *
 * 1. read the ticket (short read-only transaction)
 * 2. call ai-service (NO transaction or DB connection held while the LLM thinks)
 * 3. apply the suggestion through the normal domain method {@link Ticket#triage}, so
 *    the AI goes through the same business rules as a human (new short transaction)
 *
 * Every suggestion is also stored as an internal AI comment, so there is an audit trail
 * of what the AI proposed and why, even when it was not applied.
 *
 * Step 3 is public ({@link #recordSuggestion}) so the asynchronous path (stage 5b, results
 * arriving over RabbitMQ) applies exactly the same confidence rule and audit comment.
 */
@Service
public class AiTriageService {

    public static final String AI_AUTHOR = "AI triage assistant";

    private final TicketRepository ticketRepository;
    private final AiTriageClient aiClient;
    private final AiServiceProperties props;
    private final TransactionTemplate readTx;
    private final TransactionTemplate writeTx;
    private final Clock clock;

    public AiTriageService(TicketRepository ticketRepository, AiTriageClient aiClient, AiServiceProperties props,
                           PlatformTransactionManager txManager, Clock clock) {
        this.ticketRepository = ticketRepository;
        this.aiClient = aiClient;
        this.props = props;
        this.clock = clock;
        this.writeTx = new TransactionTemplate(txManager);
        this.readTx = new TransactionTemplate(txManager);
        this.readTx.setReadOnly(true);
    }

    public AiTriageResponse triage(Long ticketId, boolean apply) {
        // 1. snapshot what the AI needs
        Snapshot snapshot = readTx.execute(status -> {
            Ticket ticket = load(ticketId);
            requireNotClosed(ticket);
            return new Snapshot(toRequest(ticket), TicketDetailResponse.from(ticket, clock.instant()));
        });

        // 2. ask the AI (may take seconds)
        AiTriageResult result = aiClient.triage(snapshot.request());

        if (!apply) {
            return new AiTriageResponse(ticketId, Outcome.PREVIEW, result, snapshot.ticket());
        }

        // 3. record and (if confident enough) apply
        return writeTx.execute(status -> {
            Ticket t = load(ticketId);
            requireNotClosed(t); // it may have been closed while the AI was thinking
            Instant now = clock.instant();
            Outcome outcome = recordSuggestion(t, result, now, "AI triage");
            ticketRepository.flush();
            return new AiTriageResponse(ticketId, outcome, result, TicketDetailResponse.from(t, now));
        });
    }

    /**
     * Applies the suggestion if confidence is high enough, and always adds the internal AI
     * comment. Must be called inside a transaction with a loaded, non-closed ticket.
     *
     * @param label first words of the comment, e.g. "AI triage" or "AI auto-triage"
     */
    public Outcome recordSuggestion(Ticket t, AiTriageResult result, Instant now, String label) {
        boolean confident = result.confidence() >= props.minConfidence();
        if (confident) {
            t.triage(result.category(), result.priority(), now);
        }
        t.addComment(AuthorType.AI, AI_AUTHOR, describe(result, confident, label), true, now);
        return confident ? Outcome.APPLIED : Outcome.LOW_CONFIDENCE;
    }

    /** What ai-service needs to know about a ticket. Shared with the async publisher. */
    public static AiTriageRequest toRequest(Ticket t) {
        CustomerOrder o = t.getRelatedOrder();
        OrderContext order = o == null ? null
                : new OrderContext(o.getOrderNumber(), o.getProductName(), o.getStatus(), o.getAmount());
        return new AiTriageRequest(t.getId(), t.getSubject(), t.getDescription(), t.getCustomer().getTier(),
                t.getPriority(), order);
    }

    private String describe(AiTriageResult r, boolean applied, String label) {
        String header = applied
                ? label + " applied"
                : String.format(Locale.ROOT, "%s suggestion NOT applied (confidence below %.2f, needs human review)",
                label, props.minConfidence());
        return String.format(Locale.ROOT,
                "%s: category %s, priority %s, sentiment %s, confidence %.2f.%nSummary: %s%nReasoning: %s%n(%s via %s, %d ms)",
                header, r.category(), r.priority(), r.sentiment(), r.confidence(), r.summary(), r.reasoning(),
                r.model(), r.provider(), r.latencyMs());
    }

    private Ticket load(Long id) {
        return ticketRepository.findWithDetailsById(id).orElseThrow(() -> new NotFoundException("Ticket", id));
    }

    private static void requireNotClosed(Ticket t) {
        if (t.getStatus() == TicketStatus.CLOSED) {
            throw new BusinessRuleException("TICKET_CLOSED", "Ticket " + t.getId() + " is closed");
        }
    }

    private record Snapshot(AiTriageRequest request, TicketDetailResponse ticket) {
    }
}
