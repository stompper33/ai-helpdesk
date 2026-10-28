package com.helpdesk.service;

import com.helpdesk.domain.Enums.TicketCategory;
import com.helpdesk.domain.Ticket;
import com.helpdesk.domain.TicketPriority;
import com.helpdesk.domain.TicketStatus;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * Optional filters for the ticket queue. Every field is nullable; null means "don't filter".
 * {@code unassigned} and {@code slaBreached} make handy queries for humans and, later, the AI agent.
 */
public record TicketSearchCriteria(TicketStatus status, TicketPriority priority, TicketCategory category,
                                   Long customerId, Long assigneeId, Boolean unassigned, Boolean slaBreached,
                                   Boolean untriaged) {

    public Specification<Ticket> toSpecification(Instant now)
    {
        return (root, query, cb) -> {
            List<Predicate> conditions = new ArrayList<>();
            if (status != null) conditions.add(cb.equal(root.get("status"), status));
            if (priority != null) conditions.add(cb.equal(root.get("priority"), priority));
            if (category != null) conditions.add(cb.equal(root.get("category"), category));
            if (customerId != null) conditions.add(cb.equal(root.get("customer").get("id"), customerId));
            if (assigneeId != null) conditions.add(cb.equal(root.get("assignee").get("id"), assigneeId));
            if (Boolean.TRUE.equals(unassigned)) conditions.add(cb.isNull(root.get("assignee")));
            if (Boolean.TRUE.equals(untriaged)) conditions.add(cb.isNull(root.get("category")));
            if (Boolean.TRUE.equals(slaBreached)) {
                conditions.add(root.get("status").in(EnumSet.of(
                        TicketStatus.OPEN, TicketStatus.IN_PROGRESS, TicketStatus.WAITING_ON_CUSTOMER)));
                conditions.add(cb.lessThan(root.<Instant>get("slaDueAt"), now));
            }
            return cb.and(conditions.toArray(new Predicate[0]));
        };
    }
}
