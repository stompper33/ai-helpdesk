package com.helpdesk.actions;

import com.helpdesk.actions.ProposedActionDtos.DecisionRequest;
import com.helpdesk.actions.ProposedActionDtos.ProposedActionResponse;
import com.helpdesk.domain.*;
import com.helpdesk.domain.HelpDeskConstants.ErrorCodes;
import com.helpdesk.domain.Enums.AuthorType;
import com.helpdesk.domain.Enums.OrderStatus;
import com.helpdesk.domain.ProposedAction.Status;
import com.helpdesk.domain.ProposedAction.Type;
import com.helpdesk.exception.BusinessRuleException;
import com.helpdesk.exception.NotFoundException;
import com.helpdesk.repository.OrderRepository;
import com.helpdesk.repository.ProposedActionRepository;
import com.helpdesk.repository.SupportAgentRepository;
import com.helpdesk.repository.TicketRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Human-in-the-loop for AI-suggested actions.
 *
 * propose: called when the agent drafts a reply; creates PENDING rows (no side effects).
 * approve: a support agent says yes; the action is executed through the domain in the same
 *          transaction. If execution breaks a business rule (e.g. the order can't be refunded)
 *          everything rolls back and the action stays PENDING, so it can be rejected instead.
 * reject:  a support agent says no; nothing is executed.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class ProposedActionService {

    private static final Logger log = LoggerFactory.getLogger(ProposedActionService.class);

    private final ProposedActionRepository actionRepository;
    private final TicketRepository ticketRepository;
    private final SupportAgentRepository agentRepository;
    private final OrderRepository orderRepository;
    private final Clock clock;

    // ---- proposing (called by AiDraftService inside its transaction) ---------------

    /**
     * Turns the agent's suggestedActions into PENDING proposals. Unknown values and NO_ACTION are
     * ignored, and a type that is already pending for the ticket is not duplicated.
     */
    @Transactional
    public List<ProposedActionResponse> propose(Ticket ticket, List<String> suggested, String proposedBy,
                                                String reason, Instant now) {
        List<ProposedActionResponse> created = new ArrayList<>();
        if (suggested == null) {
            return created;
        }
        for (String value : suggested.stream().distinct().toList()) {
            Type type = parse(value);
            if (type == null || actionRepository.existsByTicketIdAndTypeAndStatus(ticket.getId(), type, Status.PENDING)) {
                continue;
            }
            ProposedAction saved = actionRepository.save(new ProposedAction(ticket, type, proposedBy, reason, now));
            created.add(ProposedActionResponse.from(saved));
        }
        return created;
    }

    // ---- reading ------------------------------------------------------------------

    public List<ProposedActionResponse> list(Status status) {
        return actionRepository.findByStatusOrderByCreatedAtAsc(status).stream().map(ProposedActionResponse::from).toList();
    }

    public List<ProposedActionResponse> listForTicket(Long ticketId) {
        if (!ticketRepository.existsById(ticketId)) {
            throw new NotFoundException("Ticket", ticketId);
        }
        return actionRepository.findByTicketIdOrderByCreatedAtAsc(ticketId).stream().map(ProposedActionResponse::from).toList();
    }

    // ---- deciding -----------------------------------------------------------------

    @Transactional
    public ProposedActionResponse approve(Long actionId, DecisionRequest req) {
        ProposedAction action = load(actionId);
        SupportAgent agent = loadAgent(req.agentId());
        if (action.getStatus() != Status.PENDING) {
            throw new BusinessRuleException(ErrorCodes.ACTION_ALREADY_DECIDED,
                    "Action " + actionId + " is already " + action.getStatus());
        }
        Ticket ticket = action.getTicket();
        requireNotClosed(ticket);

        Instant now = clock.instant();
        String result = execute(action.getType(), ticket, agent, now);
        action.markExecuted(agent, req.note(), result, now);
        ticket.addComment(AuthorType.SYSTEM, "system",
                "Approved AI-proposed " + action.getType() + " (action " + action.getId() + ") by " + agent.getName()
                        + ": " + result, true, now);
        log.info("Action {} {} on ticket {} approved by {}: {}", action.getId(), action.getType(), ticket.getId(),
                agent.getName(), result);
        return ProposedActionResponse.from(action);
    }

    @Transactional
    public ProposedActionResponse reject(Long actionId, DecisionRequest req) {
        ProposedAction action = load(actionId);
        SupportAgent agent = loadAgent(req.agentId());
        Instant now = clock.instant();
        action.reject(agent, req.note(), now);
        if (action.getTicket().getStatus() != TicketStatus.CLOSED) {
            action.getTicket().addComment(AuthorType.SYSTEM, "system",
                    "Rejected AI-proposed " + action.getType() + " (action " + action.getId() + ") by "
                            + agent.getName() + (req.note() == null ? "" : ": " + req.note()), true, now);
        }
        return ProposedActionResponse.from(action);
    }

    // ---- execution: Java decides what each action means -----------------------------

    private String execute(Type type, Ticket ticket, SupportAgent approver, Instant now) {
        return switch (type) {
            case REFUND -> {
                CustomerOrder order = requireOrder(ticket, type);
                order.markRefunded();
                yield String.format(Locale.ROOT, "order %s refunded (%s)", order.getOrderNumber(), order.getAmount());
            }
            case REPLACEMENT -> {
                CustomerOrder order = requireOrder(ticket, type);
                if (order.getStatus() != OrderStatus.DELIVERED) {
                    throw new BusinessRuleException(ErrorCodes.ORDER_NOT_DELIVERED,
                            "Only delivered orders can be replaced; " + order.getOrderNumber() + " is " + order.getStatus());
                }
                CustomerOrder replacement = orderRepository.save(new CustomerOrder(nextReplacementNumber(order),
                        order.getCustomer(), order.getProductName(), BigDecimal.ZERO, OrderStatus.PLACED, null, now));
                yield "replacement order " + replacement.getOrderNumber() + " created for " + order.getOrderNumber();
            }
            case ESCALATE -> {
                TicketPriority before = ticket.getPriority();
                if (before == TicketPriority.URGENT) {
                    yield "already URGENT, priority unchanged";
                }
                TicketPriority after = TicketPriority.values()[before.ordinal() + 1];
                ticket.triage(null, after, now);
                yield "priority raised " + before + " -> " + after;
            }
            case REQUEST_MORE_INFO -> {
                if (ticket.getStatus() == TicketStatus.WAITING_ON_CUSTOMER) {
                    yield "already waiting on customer, status unchanged";
                }
                // Approving "ask the customer" means taking the ticket: an unassigned ticket is assigned
                // to the approver first (OPEN -> IN_PROGRESS), so the normal lifecycle rules still hold
                // (no ticket ever waits on a customer without an owner).
                String assigned = "";
                if (ticket.getAssignee() == null) {
                    ticket.assignTo(approver, now);
                    assigned = "assigned to " + approver.getName() + ", ";
                }
                ticket.transitionTo(TicketStatus.WAITING_ON_CUSTOMER, null, now); // e.g. RESOLVED -> 409
                yield assigned + "ticket moved to WAITING_ON_CUSTOMER";
            }
        };
    }

    private String nextReplacementNumber(CustomerOrder original) {
        for (int i = 1; ; i++) {
            String candidate = original.getOrderNumber() + "-R" + i;
            if (orderRepository.findByOrderNumberIgnoreCase(candidate).isEmpty()) {
                return candidate;
            }
        }
    }

    private static CustomerOrder requireOrder(Ticket ticket, Type type) {
        if (ticket.getRelatedOrder() == null) {
            throw new BusinessRuleException(ErrorCodes.NO_RELATED_ORDER, type + " needs a ticket linked to an order");
        }
        return ticket.getRelatedOrder();
    }

    private static void requireNotClosed(Ticket t) {
        if (t.getStatus() == TicketStatus.CLOSED) {
            throw new BusinessRuleException(ErrorCodes.TICKET_CLOSED, "Ticket " + t.getId() + " is closed");
        }
    }

    private static Type parse(String value) {
        try {
            return value == null ? null : Type.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null; // NO_ACTION or anything unknown
        }
    }

    private ProposedAction load(Long id) {
        return actionRepository.findWithTicketById(id).orElseThrow(() -> new NotFoundException("Proposed action", id));
    }

    private SupportAgent loadAgent(Long id) {
        return agentRepository.findById(id).orElseThrow(() -> new NotFoundException("Support agent", id));
    }
}
