package com.helpdesk.web.dto;

import com.helpdesk.domain.*;
import com.helpdesk.domain.Enums.AuthorType;
import com.helpdesk.domain.Enums.CustomerTier;
import com.helpdesk.domain.Enums.OrderStatus;
import com.helpdesk.domain.Enums.TicketCategory;
import org.springframework.data.domain.Page;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * Outbound response bodies. Entities never leave the service layer, so the API
 * contract stays stable even if the database model changes.
 */
public final class Responses {

    private Responses() {
    }

    public record CustomerResponse(Long id, String name, String email, CustomerTier tier, Instant createdAt) {
        public static CustomerResponse from(Customer c) {
            return new CustomerResponse(c.getId(), c.getName(), c.getEmail(), c.getTier(), c.getCreatedAt());
        }
    }

    public record OrderResponse(Long id, String orderNumber, Long customerId, String productName,
                                BigDecimal amount, OrderStatus status, String trackingNumber, Instant orderedAt) {
        public static OrderResponse from(CustomerOrder o) {
            return new OrderResponse(o.getId(), o.getOrderNumber(), o.getCustomer().getId(), o.getProductName(),
                    o.getAmount(), o.getStatus(), o.getTrackingNumber(), o.getOrderedAt());
        }
    }

    public record AgentResponse(Long id, String name, String email, String team) {
        public static AgentResponse from(SupportAgent a) {
            return a == null ? null : new AgentResponse(a.getId(), a.getName(), a.getEmail(), a.getTeam());
        }
    }

    public record CommentResponse(Long id, AuthorType authorType, String authorName, String body,
                                  boolean internal, Instant createdAt) {
        public static CommentResponse from(TicketComment c) {
            return new CommentResponse(c.getId(), c.getAuthorType(), c.getAuthorName(), c.getBody(),
                    c.isInternal(), c.getCreatedAt());
        }
    }

    public record TicketSummaryResponse(Long id, String subject, TicketStatus status, TicketPriority priority,
                                        TicketCategory category, Long customerId, String customerName,
                                        String orderNumber, Long assigneeId, String assigneeName,
                                        Instant createdAt, Instant slaDueAt, boolean slaBreached) {
        public static TicketSummaryResponse from(Ticket t, Instant now) {
            SupportAgent a = t.getAssignee();
            return new TicketSummaryResponse(t.getId(), t.getSubject(), t.getStatus(), t.getPriority(),
                    t.getCategory(), t.getCustomer().getId(), t.getCustomer().getName(),
                    t.getRelatedOrder() == null ? null : t.getRelatedOrder().getOrderNumber(),
                    a == null ? null : a.getId(), a == null ? null : a.getName(),
                    t.getCreatedAt(), t.getSlaDueAt(), t.isSlaBreached(now));
        }
    }

    public record TicketDetailResponse(Long id, String subject, String description, TicketStatus status,
                                       TicketPriority priority, TicketCategory category,
                                       CustomerResponse customer, OrderResponse order, AgentResponse assignee,
                                       Instant createdAt, Instant updatedAt, Instant slaDueAt,
                                       Instant resolvedAt, boolean slaBreached,
                                       Set<TicketStatus> allowedNextStatuses,
                                       List<CommentResponse> comments) {
        public static TicketDetailResponse from(Ticket t, Instant now) {
            return new TicketDetailResponse(t.getId(), t.getSubject(), t.getDescription(), t.getStatus(),
                    t.getPriority(), t.getCategory(), CustomerResponse.from(t.getCustomer()),
                    t.getRelatedOrder() == null ? null : OrderResponse.from(t.getRelatedOrder()),
                    AgentResponse.from(t.getAssignee()),
                    t.getCreatedAt(), t.getUpdatedAt(), t.getSlaDueAt(), t.getResolvedAt(),
                    t.isSlaBreached(now), t.getStatus().allowedNext(),
                    t.getComments().stream().map(CommentResponse::from).toList());
        }
    }

    /** Plain paging wrapper, so the JSON shape doesn't depend on Spring's Page internals. */
    public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {
        public static <E, T> PageResponse<T> from(Page<E> page, Function<E, T> mapper) {
            return new PageResponse<>(page.getContent().stream().map(mapper).toList(),
                    page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
        }
    }
}
