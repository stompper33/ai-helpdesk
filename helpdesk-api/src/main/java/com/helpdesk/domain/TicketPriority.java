package com.helpdesk.domain;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;

import java.time.Duration;

@Getter
public enum TicketPriority {
    LOW(Duration.ofHours(72)),
    MEDIUM(Duration.ofHours(24)),
    HIGH(Duration.ofHours(8)),
    URGENT(Duration.ofHours(4));

    private final Duration baseResponseTime;

    TicketPriority(Duration baseResponseTime) {
        this.baseResponseTime = baseResponseTime;
    }
}
