package com.helpdesk.domain;

/**
 * Small enums grouped in one file for readability. Each is a public nested type,
 * referenced as e.g. {@code Enums.TicketCategory} or via static import.
 */
public final class Enums {

    private Enums() {
    }

    /** Set by a human or (later) by the AI triage agent. Null means "not triaged yet". */
    public enum TicketCategory {
        BILLING, SHIPPING, RETURNS, TECHNICAL, ACCOUNT, OTHER
    }

    public enum CustomerTier {
        STANDARD, PREMIUM
    }

    public enum OrderStatus {
        PLACED, SHIPPED, DELIVERED, CANCELLED, REFUNDED
    }

    /** AI is reserved for comments written by the Python agent in a later stage. */
    public enum AuthorType {
        CUSTOMER, AGENT, SYSTEM, AI
    }
}
