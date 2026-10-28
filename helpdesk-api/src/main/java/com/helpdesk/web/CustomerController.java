package com.helpdesk.web;

import com.helpdesk.service.CustomerService;
import com.helpdesk.service.TicketSearchCriteria;
import com.helpdesk.service.TicketService;
import com.helpdesk.web.dto.Requests.CreateCustomerRequest;
import com.helpdesk.web.dto.Responses.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api")
@Tag(name = "Customers, orders & agents")
@RequiredArgsConstructor
public class CustomerController {

    private final CustomerService customerService;
    private final TicketService ticketService;

    @GetMapping("/customers")
    public List<CustomerResponse> listCustomers() {
        return customerService.listCustomers();
    }

    @GetMapping("/customers/{id}")
    public CustomerResponse getCustomer(@PathVariable Long id) {
        return customerService.getCustomer(id);
    }

    @PostMapping("/customers")
    @ResponseStatus(HttpStatus.CREATED)
    public CustomerResponse createCustomer(@Valid @RequestBody CreateCustomerRequest request) {
        return customerService.createCustomer(request);
    }

    @GetMapping("/customers/{id}/orders")
    @Operation(summary = "Order history for a customer, newest first")
    public List<OrderResponse> customerOrders(@PathVariable Long id) {
        return customerService.listOrdersForCustomer(id);
    }

    @GetMapping("/customers/{id}/tickets")
    @Operation(summary = "All tickets raised by a customer, newest first")
    public PageResponse<TicketSummaryResponse> customerTickets(@PathVariable Long id) {
        customerService.getCustomer(id); // 404 if the customer doesn't exist
        var criteria = new TicketSearchCriteria(null, null, null, id, null, null, null, null);
        return ticketService.search(criteria, PageRequest.of(0, 100, Sort.by(Sort.Direction.DESC, "createdAt")));
    }

    @GetMapping("/orders/{orderNumber}")
    @Operation(summary = "Look up an order by its order number (e.g. ORD-10001)")
    public OrderResponse getOrder(@PathVariable String orderNumber) {
        return customerService.getOrderByNumber(orderNumber);
    }

    @GetMapping("/agents")
    @Operation(summary = "Human support agents tickets can be assigned to")
    public List<AgentResponse> listAgents() {
        return customerService.listAgents();
    }
}
