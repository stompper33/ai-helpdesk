package com.helpdesk.domain;

import com.helpdesk.exception.BusinessRuleException;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import com.helpdesk.domain.HelpDeskConstants.ErrorCodes;

import java.time.Instant;

/**
 * Stage 5c: an action the AI suggested, waiting for a human decision.
 *
 * The AI can only propose; it never executes anything. A support agent approves (Java then
 * executes it through the domain) or rejects it, and who decided, when and why is kept.
 *
 * {@code @Version} gives optimistic locking: if two agents approve the same action at the same
 * moment, the second commit fails instead of refunding twice.
 */
@Entity
@Table(name = "proposed_actions")
@Getter
@Setter
public class ProposedAction {

    public enum Type { REFUND, REPLACEMENT, ESCALATE, REQUEST_MORE_INFO }

    public enum Status { PENDING, EXECUTED, REJECTED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Ticket ticket;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Type type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(nullable = false)
    private String proposedBy;

    @Column(length = 1000)
    private String reason;

    @Column(nullable = false)
    private Instant createdAt;

    @ManyToOne(fetch = FetchType.LAZY)
    private SupportAgent decidedBy;

    private Instant decidedAt;

    @Column(length = 1000)
    private String decisionNote;

    @Column(length = 1000)
    private String executionResult;

    @Version
    private Long version;

    protected ProposedAction() {
    }

    public ProposedAction(Ticket ticket, Type type, String proposedBy, String reason, Instant now) {
        this.ticket = ticket;
        this.type = type;
        this.status = Status.PENDING;
        this.proposedBy = proposedBy;
        this.reason = truncate(reason);
        this.createdAt = now;
    }

    public void markExecuted(SupportAgent agent, String note, String result, Instant now) {
        decide(agent, note, now);
        this.status = Status.EXECUTED;
        this.executionResult = truncate(result);
    }

    public void reject(SupportAgent agent, String note, Instant now) {
        decide(agent, note, now);
        this.status = Status.REJECTED;
    }

    private void decide(SupportAgent agent, String note, Instant now) {
        if (status != Status.PENDING) {
            throw new BusinessRuleException(ErrorCodes.ACTION_ALREADY_DECIDED,
                    "Action " + id + " is already " + status + " (decided by "
                            + (decidedBy == null ? "unknown" : decidedBy.getName()) + ")");
        }
        this.decidedBy = agent;
        this.decisionNote = truncate(note);
        this.decidedAt = now;
    }

    private static String truncate(String s) {
        return s == null || s.length() <= 1000 ? s : s.substring(0, 997) + "...";
    }
}
