package com.helpdesk.web.dto;

import com.helpdesk.domain.Enums.AuthorType;
import com.helpdesk.domain.Enums.CustomerTier;
import com.helpdesk.domain.Enums.TicketCategory;
import com.helpdesk.domain.TicketPriority;
import com.helpdesk.domain.TicketStatus;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Inbound request bodies, grouped in one place. */
public final class Requests {

    private Requests() {
    }

    public record CreateCustomerRequest(
            @NotBlank @Size(max = 120) String name,
            @NotBlank @Email String email,
            CustomerTier tier) {
    }

    public record CreateTicketRequest(
            @NotNull Long customerId,
            String orderNumber,
            @NotBlank @Size(max = 200) String subject,
            @NotBlank @Size(max = 4000) String description,
            TicketPriority priority) {
    }

    /** Used by humans today and by the AI triage agent later. */
    public record TriageRequest(TicketCategory category, TicketPriority priority) {

        @JsonIgnore
        @AssertTrue(message = "Provide a category, a priority, or both")
        public boolean isAnyFieldPresent() {
            return category != null || priority != null;
        }
    }

    public record AssignRequest(@NotNull Long agentId) {
    }

    public record StatusChangeRequest(@NotNull TicketStatus status, @Size(max = 4000) String note) {
    }

    public record AddCommentRequest(
            @NotNull AuthorType authorType,
            @NotBlank @Size(max = 120) String authorName,
            @NotBlank @Size(max = 4000) String body,
            boolean internal) {
    }
}
