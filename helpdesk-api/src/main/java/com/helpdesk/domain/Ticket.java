package com.helpdesk.domain;

import com.helpdesk.domain.Enums.AuthorType;
import com.helpdesk.domain.Enums.CustomerTier;
import com.helpdesk.domain.HelpDeskConstants.ErrorCodes;
import com.helpdesk.domain.Enums.TicketCategory;
import com.helpdesk.exception.BusinessRuleException;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The core aggregate. Business rules live on the entity itself so they apply
 * no matter who calls them: a human via REST today, the AI agent tomorrow.
 */
@Entity
@Table(name = "tickets")
@Getter
@Setter
public class Ticket {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Customer customer;

    /** Optional: the order this ticket is about. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id")
    private CustomerOrder relatedOrder;

    @Column(nullable = false)
    private String subject;

    @Column(nullable = false, length = 4000)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TicketStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TicketPriority priority;

    @Enumerated(EnumType.STRING)
    private TicketCategory category;

    @ManyToOne(fetch = FetchType.LAZY)
    private SupportAgent assignee;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Column(nullable = false)
    private Instant slaDueAt;

    private Instant resolvedAt;

    @OneToMany(mappedBy = "ticket", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("createdAt ASC, id ASC")
    private List<TicketComment> comments = new ArrayList<>();

    protected Ticket() {
    }

    public Ticket(Customer customer, CustomerOrder order, String subject, String description,
                  TicketPriority priority, Instant now) {
        if (order != null && !order.getCustomer().getId().equals(customer.getId())) {
            throw new BusinessRuleException(ErrorCodes.ORDER_CUSTOMER_MISMATCH,
                    "Order " + order.getOrderNumber() + " does not belong to customer " + customer.getId());
        }
        this.customer = customer;
        this.relatedOrder = order;
        this.subject = subject;
        this.description = description;
        this.priority = priority;
        this.status = TicketStatus.OPEN;
        this.createdAt = now;
        this.updatedAt = now;
        this.slaDueAt = calculateSlaDue(now);
    }

    // ---- behaviour -------------------------------------------------------

    /** Sets category/priority. Changing priority recalculates the SLA from creation time. */
    public void triage(TicketCategory newCategory, TicketPriority newPriority, Instant now) {
        requireNotClosed();
        if (newCategory != null) {
            this.category = newCategory;
        }
        if (newPriority != null && newPriority != this.priority) {
            this.priority = newPriority;
            this.slaDueAt = calculateSlaDue(this.createdAt);
        }
        touch(now);
    }

    /** Assigning an OPEN ticket starts work on it. */
    public void assignTo(SupportAgent agent, Instant now) {
        requireNotClosed();
        this.assignee = agent;
        addComment(AuthorType.SYSTEM, "system", "Assigned to " + agent.getName(), true, now);
        if (status == TicketStatus.OPEN) {
            changeStatus(TicketStatus.IN_PROGRESS, now);
        }
        touch(now);
    }

    public void transitionTo(TicketStatus next, String note, Instant now) {
        if (next == status) {
            throw new BusinessRuleException(ErrorCodes.NO_OP_TRANSITION, "Ticket is already " + status);
        }
        if (!status.canTransitionTo(next)) {
            throw new BusinessRuleException(ErrorCodes.INVALID_STATUS_TRANSITION,
                    "Cannot move ticket from " + status + " to " + next + ". Allowed: " + status.allowedNext());
        }
        if (next == TicketStatus.IN_PROGRESS && assignee == null) {
            throw new BusinessRuleException(ErrorCodes.ASSIGNEE_REQUIRED, "Assign the ticket before starting work on it");
        }
        if (next == TicketStatus.RESOLVED && category == null) {
            throw new BusinessRuleException(ErrorCodes.CATEGORY_REQUIRED, "Set a category (triage) before resolving");
        }
        // Record the note first: once the ticket is CLOSED it no longer accepts comments.
        if (note != null && !note.isBlank()) {
            addComment(AuthorType.AGENT, assignee != null ? assignee.getName() : "agent", note, false, now);
        }
        changeStatus(next, now);
    }

    /** A customer replying to a ticket that waits on them puts it back in the agent's queue. */
    public TicketComment addComment(AuthorType authorType, String authorName, String body,
                                    boolean internal, Instant now) {
        requireNotClosed();
        if (authorType == AuthorType.CUSTOMER && internal) {
            throw new BusinessRuleException(ErrorCodes.CUSTOMER_INTERNAL_NOTE, "Customers cannot write internal notes");
        }
        TicketComment comment = new TicketComment(this, authorType, authorName, body, internal, now);
        comments.add(comment);
        if (authorType == AuthorType.CUSTOMER && status == TicketStatus.WAITING_ON_CUSTOMER) {
            changeStatus(TicketStatus.IN_PROGRESS, now);
        }
        touch(now);
        return comment;
    }

    public boolean isSlaBreached(Instant now) {
        return status.isActive() && now.isAfter(slaDueAt);
    }

    // ---- internals -------------------------------------------------------

    private void changeStatus(TicketStatus next, Instant now) {
        TicketStatus previous = this.status;
        this.status = next;
        this.resolvedAt = (next == TicketStatus.RESOLVED) ? now : (next == TicketStatus.CLOSED ? resolvedAt : null);
        comments.add(new TicketComment(this, AuthorType.SYSTEM, "system",
                "Status changed " + previous + " -> " + next, true, now));
        touch(now);
    }

    /** Premium customers get half the response time. */
    private Instant calculateSlaDue(Instant from) {
        Duration window = priority.getBaseResponseTime();
        if (customer.getTier() == CustomerTier.PREMIUM) {
            window = window.dividedBy(2);
        }
        return from.plus(window);
    }

    private void requireNotClosed() {
        if (status == TicketStatus.CLOSED) {
            throw new BusinessRuleException(ErrorCodes.TICKET_CLOSED, "Ticket " + id + " is closed");
        }
    }

    private void touch(Instant now) {
        this.updatedAt = now;
    }
}
