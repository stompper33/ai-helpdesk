package com.helpdesk;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end tests through the HTTP layer against the in-memory database.
 * Each test creates its own customer so tests don't depend on each other.
 */
@SpringBootTest
@AutoConfigureMockMvc
class TicketApiIntegrationTest {

    @Autowired
    MockMvc mvc;

    long customerId;
    long agentId;

    @BeforeEach
    void setUp() throws Exception {
        String email = "test-" + UUID.randomUUID() + "@example.com";
        customerId = idOf(post("/api/customers", """
                {"name": "Test Customer", "email": "%s", "tier": "PREMIUM"}
                """.formatted(email)).andExpect(status().isCreated()));

        String agents = mvc.perform(get("/api/agents")).andReturn().getResponse().getContentAsString();
        agentId = ((Number) JsonPath.read(agents, "$[0].id")).longValue();
    }

    @Test
    void newTicketStartsOpenWithPremiumSla() throws Exception {
        post("/api/tickets", ticketJson("HIGH"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.priority").value("HIGH"))
                .andExpect(jsonPath("$.category").value(nullValue()))
                .andExpect(jsonPath("$.slaBreached").value(false))
                .andExpect(jsonPath("$.allowedNextStatuses", containsInAnyOrder("IN_PROGRESS", "CLOSED")));
    }

    @Test
    void cannotJumpFromOpenToResolved() throws Exception {
        long ticketId = createTicket();

        post("/api/tickets/" + ticketId + "/status", """
                {"status": "RESOLVED"}
                """)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS_TRANSITION"));
    }

    @Test
    void cannotResolveUntriagedTicket() throws Exception {
        long ticketId = createTicket();
        assign(ticketId);

        post("/api/tickets/" + ticketId + "/status", """
                {"status": "RESOLVED"}
                """)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CATEGORY_REQUIRED"));
    }

    @Test
    void fullLifecycle() throws Exception {
        long ticketId = createTicket();

        patch("/api/tickets/" + ticketId + "/triage", """
                {"category": "SHIPPING", "priority": "URGENT"}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.category").value("SHIPPING"))
                .andExpect(jsonPath("$.priority").value("URGENT"));

        assign(ticketId).andExpect(jsonPath("$.status").value("IN_PROGRESS"));

        post("/api/tickets/" + ticketId + "/status", """
                {"status": "WAITING_ON_CUSTOMER", "note": "Can you send a photo of the package?"}
                """)
                .andExpect(jsonPath("$.status").value("WAITING_ON_CUSTOMER"));

        // Customer reply puts it back in the agent's queue
        post("/api/tickets/" + ticketId + "/comments", """
                {"authorType": "CUSTOMER", "authorName": "Test Customer", "body": "Photo attached", "internal": false}
                """)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber());

        mvc.perform(get("/api/tickets/" + ticketId))
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"));

        post("/api/tickets/" + ticketId + "/status", """
                {"status": "RESOLVED", "note": "Replacement shipped."}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(jsonPath("$.resolvedAt").isNotEmpty());

        // Customer-facing view hides internal system notes
        mvc.perform(get("/api/tickets/" + ticketId + "/comments").param("includeInternal", "false"))
                .andExpect(jsonPath("$[*].internal", everyItem(is(false))));
    }

    @Test
    void ticketCannotReferenceAnotherCustomersOrder() throws Exception {
        // ORD-10001 is seeded for a different customer
        post("/api/tickets", """
                {"customerId": %d, "orderNumber": "ORD-10001", "subject": "s", "description": "d"}
                """.formatted(customerId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_CUSTOMER_MISMATCH"));
    }

    @Test
    void invalidRequestReturnsFieldErrors() throws Exception {
        post("/api/tickets", """
                {"customerId": %d, "subject": ""}
                """.formatted(customerId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors.subject").exists())
                .andExpect(jsonPath("$.errors.description").exists());
    }

    @Test
    void unknownTicketReturns404() throws Exception {
        mvc.perform(get("/api/tickets/999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void untriagedFilterFindsSeededTickets() throws Exception {
        mvc.perform(get("/api/tickets").param("untriaged", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements", greaterThanOrEqualTo(3)))
                .andExpect(jsonPath("$.content[*].category", everyItem(nullValue())));
    }

    // ---- helpers ---------------------------------------------------------

    private String ticketJson(String priority) {
        return """
                {"customerId": %d, "subject": "Package damaged", "description": "Box was crushed", "priority": "%s"}
                """.formatted(customerId, priority);
    }

    private long createTicket() throws Exception {
        return idOf(post("/api/tickets", ticketJson("MEDIUM")).andExpect(status().isCreated()));
    }

    private ResultActions assign(long ticketId) throws Exception {
        return post("/api/tickets/" + ticketId + "/assign", """
                {"agentId": %d}
                """.formatted(agentId)).andExpect(status().isOk());
    }

    private ResultActions post(String url, String json) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(url)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private ResultActions patch(String url, String json) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(url)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private static long idOf(ResultActions result) throws Exception {
        String body = result.andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }
}
