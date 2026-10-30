package com.helpdesk.domain;

public final class HelpDeskConstants {

    private HelpDeskConstants(){}

    public static final class ErrorCodes
    {
        private ErrorCodes(){}
        public static final String ACTION_ALREADY_DECIDED = "ACTION_ALREADY_DECIDED";
        public static final String ORDER_NOT_DELIVERED = "ORDER_NOT_DELIVERED";
        public static final String NO_RELATED_ORDER = "NO_RELATED_ORDER";
        public static final String TICKET_CLOSED = "TICKET_CLOSED";
        public static final String ORDER_ALREADY_REFUNDED = "ORDER_ALREADY_REFUNDED";
        public static final String ORDER_NOT_REFUNDABLE = "ORDER_NOT_REFUNDABLE";
        public static final String ORDER_CUSTOMER_MISMATCH = "ORDER_CUSTOMER_MISMATCH";
        public static final String NO_OP_TRANSITION = "NO_OP_TRANSITION";
        public static final String INVALID_STATUS_TRANSITION = "INVALID_STATUS_TRANSITION";
        public static final String ASSIGNEE_REQUIRED = "ASSIGNEE_REQUIRED";
        public static final String CATEGORY_REQUIRED = "CATEGORY_REQUIRED";
        public static final String CUSTOMER_INTERNAL_NOTE = "CUSTOMER_INTERNAL_NOTE";
        public static final String EMAIL_TAKEN = "EMAIL_TAKEN";
        public static final String SYSTEM_AUTHOR_RESERVED = "SYSTEM_AUTHOR_RESERVED";
    }
}
