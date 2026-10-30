package com.helpdesk.service;

import com.helpdesk.domain.*;
import com.helpdesk.domain.Enums.AuthorType;
import com.helpdesk.exception.BusinessRuleException;
import com.helpdesk.domain.HelpDeskConstants.ErrorCodes;
import com.helpdesk.exception.NotFoundException;
import com.helpdesk.repository.CustomerRepository;
import com.helpdesk.repository.OrderRepository;
import com.helpdesk.repository.SupportAgentRepository;
import com.helpdesk.repository.TicketRepository;
import com.helpdesk.web.dto.Requests.*;
import com.helpdesk.web.dto.Responses.CommentResponse;
import com.helpdesk.web.dto.Responses.PageResponse;
import com.helpdesk.web.dto.Responses.TicketDetailResponse;
import com.helpdesk.web.dto.Responses.TicketSummaryResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Application service for ticketRepository. It loads entities, calls the domain behavior
 * on {@link Ticket}, and maps results to DTOs. The rules themselves live on the entity.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class TicketService {

    private final TicketRepository ticketRepository;
    private final CustomerRepository customerRepository;
    private final OrderRepository orderRepository;
    private final SupportAgentRepository agentRepository;
    private final Clock clock;
    private final ApplicationEventPublisher events;

    @Transactional
    public TicketDetailResponse create(CreateTicketRequest req) {
        Customer customer = customerRepository.findById(req.customerId())
                .orElseThrow(() -> new NotFoundException("Customer", req.customerId()));
        CustomerOrder order = null;
        if (req.orderNumber() != null && !req.orderNumber().isBlank()) {
            order = orderRepository.findByOrderNumberIgnoreCase(req.orderNumber())
                    .orElseThrow(() -> new NotFoundException("Order", req.orderNumber()));
        }
        TicketPriority priority = req.priority() != null ? req.priority() : TicketPriority.MEDIUM;
        Ticket saved = ticketRepository.save(new Ticket(customer, order, req.subject(), req.description(), priority, now()));
        events.publishEvent(new TicketCreatedEvent(saved.getId()));
        return TicketDetailResponse.from(saved, now());
    }

    public PageResponse<TicketSummaryResponse> search(TicketSearchCriteria criteria, Pageable pageable) {
        Instant now = now();
        return PageResponse.from(ticketRepository.findAll(criteria.toSpecification(now), pageable),
                t -> TicketSummaryResponse.from(t, now));
    }

    public TicketDetailResponse get(Long id) {
        return TicketDetailResponse.from(load(id), now());
    }

    @Transactional
    public TicketDetailResponse triage(Long id, TriageRequest req) {
        Ticket ticket = load(id);
        ticket.triage(req.category(), req.priority(), now());
        return detailAfterWrite(ticket);
    }

    @Transactional
    public TicketDetailResponse assign(Long id, AssignRequest req) {
        Ticket ticket = load(id);
        SupportAgent agent = agentRepository.findById(req.agentId())
                .orElseThrow(() -> new NotFoundException("Support agent", req.agentId()));
        ticket.assignTo(agent, now());
        return detailAfterWrite(ticket);
    }

    @Transactional
    public TicketDetailResponse changeStatus(Long id, StatusChangeRequest req) {
        Ticket ticket = load(id);
        ticket.transitionTo(req.status(), req.note(), now());
        return detailAfterWrite(ticket);
    }

    @Transactional
    public CommentResponse addComment(Long id, AddCommentRequest req) {
        if (req.authorType() == AuthorType.SYSTEM) {
            throw new BusinessRuleException(ErrorCodes.SYSTEM_AUTHOR_RESERVED, "SYSTEM comments are created by the application only");
        }
        Ticket ticket = load(id);
        TicketComment comment = ticket.addComment(req.authorType(), req.authorName(), req.body(), req.internal(), now());
        ticketRepository.flush(); // so the new comment has its generated id in the response
        return CommentResponse.from(comment);
    }

    public List<CommentResponse> listComments(Long id, boolean includeInternal) {
        return load(id).getComments().stream()
                .filter(c -> includeInternal || !c.isInternal())
                .map(CommentResponse::from)
                .toList();
    }

    /** Flush first so comments created during this request carry their generated ids. */
    private TicketDetailResponse detailAfterWrite(Ticket ticket) {
        ticketRepository.flush();
        return TicketDetailResponse.from(ticket, now());
    }

    private Ticket load(Long id) {
        return ticketRepository.findWithDetailsById(id).orElseThrow(() -> new NotFoundException("Ticket", id));
    }

    private Instant now() {
        return clock.instant();
    }
}
