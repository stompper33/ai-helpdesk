package com.helpdesk.ai.autotriage;

import com.helpdesk.ai.AiDtos.AiTriageResult;
import com.helpdesk.ai.AiDtos.Outcome;
import com.helpdesk.ai.AiTriageService;
import com.helpdesk.domain.Enums.AuthorType;
import com.helpdesk.domain.Ticket;
import com.helpdesk.domain.TicketStatus;
import com.helpdesk.exception.NotFoundException;
import com.helpdesk.repository.TicketRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;

/**
 * Applies a triage result that arrived asynchronously.
 *
 * Idempotent on purpose: RabbitMQ delivers "at least once", so the same result can arrive
 * twice (e.g. after a reconnect). A ticket is only auto-triaged if nobody has triaged it yet:
 * no category (a human may have triaged it while the message was queued; the human wins)
 * and no earlier AI triage comment.
 *
 * Not tied to RabbitMQ, so it is tested without a broker.
 */
@Service
public class AutoTriageService {

    public enum Result { APPLIED, LOW_CONFIDENCE, SKIPPED_ALREADY_TRIAGED, SKIPPED_CLOSED }

    private final TicketRepository tickets;
    private final AiTriageService aiTriageService;
    private final TransactionTemplate tx;
    private final Clock clock;

    public AutoTriageService(TicketRepository tickets, AiTriageService aiTriageService,
                             PlatformTransactionManager txManager, Clock clock) {
        this.tickets = tickets;
        this.aiTriageService = aiTriageService;
        this.tx = new TransactionTemplate(txManager);
        this.clock = clock;
    }

    public Result apply(Long ticketId, AiTriageResult result) {
        return tx.execute(status -> {
            Ticket t = tickets.findWithDetailsById(ticketId)
                    .orElseThrow(() -> new NotFoundException("Ticket", ticketId));
            if (t.getStatus() == TicketStatus.CLOSED) {
                return Result.SKIPPED_CLOSED;
            }
            if (alreadyTriaged(t)) {
                return Result.SKIPPED_ALREADY_TRIAGED;
            }
            Outcome outcome = aiTriageService.recordSuggestion(t, result, clock.instant(), "AI auto-triage");
            return outcome == Outcome.APPLIED ? Result.APPLIED : Result.LOW_CONFIDENCE;
        });
    }

    static boolean alreadyTriaged(Ticket t) {
        return t.getCategory() != null || t.getComments().stream().anyMatch(c ->
                c.getAuthorType() == AuthorType.AI && AiTriageService.AI_AUTHOR.equals(c.getAuthorName()));
    }
}
