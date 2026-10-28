package com.helpdesk.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/** A human support agent (not to be confused with the AI agent coming later). */
@Entity
@Table(name = "support_agents")
@Getter
@Setter
public class SupportAgent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(nullable = false)
    private String team;

    protected SupportAgent() {
    }

    public SupportAgent(String name, String email, String team) {
        this.name = name;
        this.email = email;
        this.team = team;
    }
}
