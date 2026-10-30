package com.helpdesk.actions;

import com.helpdesk.actions.ProposedActionDtos.DecisionRequest;
import com.helpdesk.actions.ProposedActionDtos.ProposedActionResponse;
import com.helpdesk.domain.ProposedAction.Status;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api")
@Tag(name = "AI-proposed actions (approval queue)")
@RequiredArgsConstructor
public class ProposedActionController {

    private final ProposedActionService actionService;

    @GetMapping("/actions")
    @Operation(summary = "Approval queue: proposed actions by status (default PENDING), oldest first")
    public List<ProposedActionResponse> list(@RequestParam(defaultValue = "PENDING") Status status) {
        return actionService.list(status);
    }

    @GetMapping("/tickets/{ticketId}/actions")
    @Operation(summary = "All proposed actions for one ticket, with their decisions")
    public List<ProposedActionResponse> forTicket(@PathVariable Long ticketId) {
        return actionService.listForTicket(ticketId);
    }

    @PostMapping("/actions/{id}/approve")
    @Operation(summary = "Approve and execute (REFUND, REPLACEMENT, ESCALATE, REQUEST_MORE_INFO). "
            + "If a business rule blocks execution, nothing changes and the action stays PENDING.")
    public ProposedActionResponse approve(@PathVariable Long id, @Valid @RequestBody DecisionRequest request) {
        return actionService.approve(id, request);
    }

    @PostMapping("/actions/{id}/reject")
    @Operation(summary = "Reject: nothing is executed; the decision is recorded")
    public ProposedActionResponse reject(@PathVariable Long id, @Valid @RequestBody DecisionRequest request) {
        return actionService.reject(id, request);
    }
}
