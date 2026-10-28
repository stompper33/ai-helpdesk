package com.helpdesk.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * Ticket lifecycle. Allowed transitions live here so the rule is in one place:
 *
 * OPEN -> IN_PROGRESS | CLOSED
 * IN_PROGRESS -> WAITING_ON_CUSTOMER | RESOLVED
 * WAITING_ON_CUSTOMER -> IN_PROGRESS | RESOLVED
 * RESOLVED -> CLOSED | IN_PROGRESS (reopen)
 * CLOSED -> (terminal)
 */
public enum TicketStatus {
    OPEN,
    IN_PROGRESS,
    WAITING_ON_CUSTOMER,
    RESOLVED,
    CLOSED;

    public Set<TicketStatus> allowedNext() {
        return switch (this) {
            case OPEN -> EnumSet.of(IN_PROGRESS, CLOSED);
            case IN_PROGRESS -> EnumSet.of(WAITING_ON_CUSTOMER, RESOLVED);
            case WAITING_ON_CUSTOMER -> EnumSet.of(IN_PROGRESS, RESOLVED);
            case RESOLVED -> EnumSet.of(CLOSED, IN_PROGRESS);
            case CLOSED -> EnumSet.noneOf(TicketStatus.class);
        };
    }

    public boolean canTransitionTo(TicketStatus next) {
        return allowedNext().contains(next);
    }

    /** Statuses where the SLA clock is still running. */
    public boolean isActive() {
        return this != RESOLVED && this != CLOSED;
    }
}
