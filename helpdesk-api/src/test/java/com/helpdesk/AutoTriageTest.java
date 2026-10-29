package com.helpdesk;

import com.helpdesk.ai.AiDtos.AiTriageResult;
import com.helpdesk.ai.AiDtos.Usage;
import com.helpdesk.ai.autotriage.AutoTriageService;
import com.helpdesk.ai.autotriage.AutoTriageService.Result;
import com.helpdesk.domain.Enums.TicketCategory;
import com.helpdesk.domain.TicketPriority;
import com.helpdesk.exception.NotFoundException;
import com.helpdesk.service.TicketCreatedEvent;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import org.assertj.core.api.InstanceOfAssertFactories;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Stage 5b logic without RabbitMQ: the event is raised, and results are applied idempotently.
 * (Messaging is disabled in the default profile, so no broker is needed.)
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:auto-triage-test;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@RecordApplicationEvents
class AutoTriageTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    AutoTriageService autoTriage;

    @Autowired
    ApplicationEvents events;

    long ticketId;

    @BeforeEach
    void createTicket() throws Exception {
        String customer = mvc.perform(post("/api/customers").contentType(MediaType.APPLICATION_JSON).content("""
                        {"name": "Auto Test", "email": "auto-%s@example.com"}
                        """.formatted(UUID.randomUUID())))
                .andReturn().getResponse().getContentAsString();
        long customerId = ((Number) JsonPath.read(customer, "$.id")).longValue();
        String ticket = mvc.perform(post("/api/tickets").contentType(MediaType.APPLICATION_JSON).content("""
                        {"customerId": %d, "subject": "Late parcel", "description": "Tracking stuck", "priority": "LOW"}
                        """.formatted(customerId)))
                .andReturn().getResponse().getContentAsString();
        ticketId = ((Number) JsonPath.read(ticket, "$.id")).longValue();
    }

    @Test
    void creatingATicketRaisesTicketCreatedEvent() {
        assertThat(events.stream(TicketCreatedEvent.class).map(TicketCreatedEvent::ticketId))
                .containsExactly(ticketId);
    }

    @Test
    void confidentResultIsAppliedOnceEvenIfDeliveredTwice() throws Exception {
        assertThat(autoTriage.apply(ticketId, result(0.9))).isEqualTo(Result.APPLIED);
        assertThat(autoTriage.apply(ticketId, result(0.9))).isEqualTo(Result.SKIPPED_ALREADY_TRIAGED);

        String body = ticketJson();
        assertThat((String) JsonPath.read(body, "$.category")).isEqualTo("SHIPPING");
        assertThat((String) JsonPath.read(body, "$.priority")).isEqualTo("HIGH");
        assertThat(aiCommentBodies(body)).hasSize(1).first(InstanceOfAssertFactories.STRING).startsWith("AI auto-triage applied");
    }

    @Test
    void lowConfidenceIsRecordedOnceAndNotApplied() throws Exception {
        assertThat(autoTriage.apply(ticketId, result(0.2))).isEqualTo(Result.LOW_CONFIDENCE);
        assertThat(autoTriage.apply(ticketId, result(0.2))).isEqualTo(Result.SKIPPED_ALREADY_TRIAGED);

        String body = ticketJson();
        assertThat((Object) JsonPath.read(body, "$.category")).isNull();
        assertThat(aiCommentBodies(body)).hasSize(1).first(InstanceOfAssertFactories.STRING).contains("NOT applied");
    }

    @Test
    void humanTriageWins() throws Exception {
        mvc.perform(patch("/api/tickets/{id}/triage", ticketId).contentType(MediaType.APPLICATION_JSON)
                .content("{\"category\": \"BILLING\"}"));

        assertThat(autoTriage.apply(ticketId, result(0.99))).isEqualTo(Result.SKIPPED_ALREADY_TRIAGED);
        assertThat((String) JsonPath.read(ticketJson(), "$.category")).isEqualTo("BILLING");
    }

    @Test
    void closedTicketIsSkipped() throws Exception {
        mvc.perform(post("/api/tickets/{id}/status", ticketId).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\": \"CLOSED\"}"));

        assertThat(autoTriage.apply(ticketId, result(0.9))).isEqualTo(Result.SKIPPED_CLOSED);
    }

    @Test
    void unknownTicketThrows() {
        assertThatThrownBy(() -> autoTriage.apply(999_999L, result(0.9))).isInstanceOf(NotFoundException.class);
    }

    private String ticketJson() throws Exception {
        return mvc.perform(get("/api/tickets/{id}", ticketId)).andReturn().getResponse().getContentAsString();
    }

    private static List<String> aiCommentBodies(String ticketJson) {
        return JsonPath.read(ticketJson, "$.comments[?(@.authorType == 'AI')].body");
    }

    private static AiTriageResult result(double confidence) {
        return new AiTriageResult(TicketCategory.SHIPPING, TicketPriority.HIGH, "NEGATIVE",
                "Parcel tracking stalled.", "Shipping delay.", confidence, "fake", "fake-model", 3,
                new Usage(10, 5));
    }
}
