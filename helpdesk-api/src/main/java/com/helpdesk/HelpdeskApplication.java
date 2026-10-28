package com.helpdesk;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.time.Clock;

@SpringBootApplication
public class HelpdeskApplication {

    public static void main(String[] args) {
        SpringApplication.run(HelpdeskApplication.class, args);
    }

    /** Injected wherever "now" matters (SLA deadlines), so tests can control time. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
