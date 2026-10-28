package com.helpdesk.web;

import com.helpdesk.domain.Enums.TicketCategory;
import com.helpdesk.domain.TicketPriority;
import com.helpdesk.domain.TicketStatus;
import com.helpdesk.service.TicketSearchCriteria;
import com.helpdesk.service.TicketService;
import com.helpdesk.web.dto.Requests.*;
import com.helpdesk.web.dto.Responses.CommentResponse;
import com.helpdesk.web.dto.Responses.PageResponse;
import com.helpdesk.web.dto.Responses.TicketDetailResponse;
import com.helpdesk.web.dto.Responses.TicketSummaryResponse;
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
@RequestMapping("/api/tickets")
@Tag(name = "Tickets")
@RequiredArgsConstructor
public class TicketController {

    private static final int MAX_PAGE_SIZE = 100;

    private final TicketService ticketService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Raise a new ticket (starts OPEN, SLA deadline calculated from priority and customer tier)")
    public TicketDetailResponse create(@Valid @RequestBody CreateTicketRequest request) {
        return ticketService.create(request);
    }

    @GetMapping
    @Operation(summary = "Search the ticket queue. All filters are optional and combine with AND.")
    public PageResponse<TicketSummaryResponse> search(
            @RequestParam(required = false) TicketStatus status,
            @RequestParam(required = false) TicketPriority priority,
            @RequestParam(required = false) TicketCategory category,
            @RequestParam(required = false) Long customerId,
            @RequestParam(required = false) Long assigneeId,
            @RequestParam(required = false) Boolean unassigned,
            @RequestParam(required = false) Boolean slaBreached,
            @RequestParam(required = false) Boolean untriaged,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        var criteria = new TicketSearchCriteria(status, priority, category, customerId, assigneeId,
                unassigned, slaBreached, untriaged);
        var pageable = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE),
                Sort.by(Sort.Direction.DESC, "createdAt"));
        return ticketService.search(criteria, pageable);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Full ticket with customer, order, assignee, allowed next statuses and conversation")
    public TicketDetailResponse get(@PathVariable Long id) {
        return ticketService.get(id);
    }

    @PatchMapping("/{id}/triage")
    @Operation(summary = "Set category and/or priority. Changing priority recalculates the SLA deadline.")
    public TicketDetailResponse triage(@PathVariable Long id, @Valid @RequestBody TriageRequest request) {
        return ticketService.triage(id, request);
    }

    @PostMapping("/{id}/assign")
    @Operation(summary = "Assign to a support agent. An OPEN ticket moves to IN_PROGRESS.")
    public TicketDetailResponse assign(@PathVariable Long id, @Valid @RequestBody AssignRequest request) {
        return ticketService.assign(id, request);
    }

    @PostMapping("/{id}/status")
    @Operation(summary = "Move the ticket through its lifecycle. Invalid transitions return 409.")
    public TicketDetailResponse changeStatus(@PathVariable Long id, @Valid @RequestBody StatusChangeRequest request) {
        return ticketService.changeStatus(id, request);
    }

    @PostMapping("/{id}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Add a reply or internal note. A customer reply moves WAITING_ON_CUSTOMER back to IN_PROGRESS.")
    public CommentResponse addComment(@PathVariable Long id, @Valid @RequestBody AddCommentRequest request) {
        return ticketService.addComment(id, request);
    }

    @GetMapping("/{id}/comments")
    @Operation(summary = "Conversation thread. Set includeInternal=false for the customer-facing view.")
    public List<CommentResponse> comments(@PathVariable Long id,
                                          @RequestParam(defaultValue = "true") boolean includeInternal) {
        return ticketService.listComments(id, includeInternal);
    }
}
