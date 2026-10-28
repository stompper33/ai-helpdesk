package com.helpdesk.domain;

import com.helpdesk.domain.Enums.AuthorType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "ticket_comments")
@Getter
@Setter
public class TicketComment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Ticket ticket;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AuthorType authorType;

    @Column(nullable = false)
    private String authorName;

    @Column(nullable = false, length = 4000)
    private String body;

    /** Internal notes are visible to support staff only, never to the customer. */
    @Column(name = "internal_note", nullable = false)
    private boolean internal;

    @Column(nullable = false)
    private Instant createdAt;

    protected TicketComment() {
    }

    TicketComment(Ticket ticket, AuthorType authorType, String authorName, String body,
                  boolean internal, Instant createdAt) {
        this.ticket = ticket;
        this.authorType = authorType;
        this.authorName = authorName;
        this.body = body;
        this.internal = internal;
        this.createdAt = createdAt;
    }
}
