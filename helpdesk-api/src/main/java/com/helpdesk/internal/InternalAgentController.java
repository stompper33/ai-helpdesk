package com.helpdesk.internal;

import com.helpdesk.exception.NotFoundException;
import com.helpdesk.service.CustomerService;
import com.helpdesk.service.TicketSearchCriteria;
import com.helpdesk.service.TicketService;
import com.helpdesk.web.dto.Responses.OrderResponse;
import com.helpdesk.web.dto.Responses.TicketDetailResponse;
import com.helpdesk.web.dto.Responses.TicketSummaryResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The AI agent's own API (stage 5c). Compared with the public /api it is:
 *
 * - authenticated: X-Api-Key service key (ServiceKeyFilter)
 * - read-only: GET only, so a misbehaving agent can't change anything
 * - scoped to ONE ticket: every path starts with the ticket id, and data is limited to that
 *   ticket's customer here on the server. The Python tools check this too, so a bug on
 *   either side alone can't leak another customer's data (defence in depth).
 */
@RestController
@RequestMapping("/internal/agent/tickets/{ticketId}")
@Tag(name = "Internal: AI agent tools (service key required)")
@RequiredArgsConstructor
public class InternalAgentController {

    private final TicketService ticketService;
    private final CustomerService customerService;

    @GetMapping
    @Operation(summary = "The ticket the agent is working on (same shape as GET /api/tickets/{id})")
    public TicketDetailResponse ticket(@PathVariable Long ticketId) {
        return ticketService.get(ticketId);
    }

    @GetMapping("/orders")
    @Operation(summary = "Orders of this ticket's customer")
    public List<OrderResponse> orders(@PathVariable Long ticketId) {
        return customerService.listOrdersForCustomer(customerOf(ticketId));
    }

    @GetMapping("/orders/{orderNumber}")
    @Operation(summary = "One order, only if it belongs to this ticket's customer (404 otherwise)")
    public OrderResponse order(@PathVariable Long ticketId, @PathVariable String orderNumber) {
        OrderResponse order = customerService.getOrderByNumber(orderNumber);
        if (!order.customerId().equals(customerOf(ticketId))) {
            throw new NotFoundException("Order", orderNumber); // same answer as "doesn't exist"
        }
        return order;
    }

    @GetMapping("/history")
    @Operation(summary = "This customer's other tickets, newest first")
    public List<TicketSummaryResponse> history(@PathVariable Long ticketId) {
        var criteria = new TicketSearchCriteria(null, null, null, customerOf(ticketId), null, null, null, null);
        return ticketService.search(criteria, PageRequest.of(0, 50, Sort.by(Sort.Direction.DESC, "createdAt")))
                .content().stream()
                .filter(t -> !t.id().equals(ticketId))
                .toList();
    }

    private Long customerOf(Long ticketId) {
        return ticketService.get(ticketId).customer().id();
    }
}
