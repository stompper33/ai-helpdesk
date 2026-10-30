package com.helpdesk.domain;

import com.helpdesk.domain.Enums.OrderStatus;
import com.helpdesk.exception.BusinessRuleException;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import com.helpdesk.domain.HelpDeskConstants.ErrorCodes;

import java.math.BigDecimal;
import java.time.Instant;

/** Named CustomerOrder because ORDER is a reserved SQL word. */
@Entity
@Table(name = "orders")
@Getter
@Setter
public class CustomerOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String orderNumber;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Customer customer;

    @Column(nullable = false)
    private String productName;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus status;

    private String trackingNumber;

    @Column(nullable = false)
    private Instant orderedAt;

    protected CustomerOrder() {
    }

    public CustomerOrder(String orderNumber, Customer customer, String productName, BigDecimal amount,
                         OrderStatus status, String trackingNumber, Instant orderedAt) {
        this.orderNumber = orderNumber;
        this.customer = customer;
        this.productName = productName;
        this.amount = amount;
        this.status = status;
        this.trackingNumber = trackingNumber;
        this.orderedAt = orderedAt;
    }

    public void markRefunded() {
        if (status == OrderStatus.REFUNDED) {
            throw new BusinessRuleException(ErrorCodes.ORDER_ALREADY_REFUNDED, "Order " + orderNumber + " was already refunded");
        }
        if (status == OrderStatus.PLACED) {
            throw new BusinessRuleException(ErrorCodes.ORDER_NOT_REFUNDABLE,
                    "Order " + orderNumber + " has not been charged yet (status PLACED); cancel it instead");
        }
        this.status = OrderStatus.REFUNDED;
    }
}
