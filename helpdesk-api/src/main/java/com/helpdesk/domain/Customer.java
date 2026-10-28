package com.helpdesk.domain;

import com.helpdesk.domain.Enums.CustomerTier;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "customers")
@Getter
@Setter
public class Customer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, unique = true)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CustomerTier tier;

    @Column(nullable = false)
    private Instant createdAt;

    protected Customer() {
    }

    public Customer(String name, String email, CustomerTier tier, Instant createdAt) {
        this.name = name;
        this.email = email;
        this.tier = tier;
        this.createdAt = createdAt;
    }
}
