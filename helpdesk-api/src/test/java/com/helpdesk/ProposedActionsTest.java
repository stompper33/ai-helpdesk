package com.helpdesk;

import com.helpdesk.ai.AgentDtos.AgentDraftResult;
import com.helpdesk.ai.AiAgentClient;
import com.helpdesk.domain.Customer;
import com.helpdesk.domain.CustomerOrder;
import com.helpdesk.domain.Enums.CustomerTier;
import com.helpdesk.domain.Enums.OrderStatus;
import com.helpdesk.repository.CustomerRepository;
import com.helpdesk.repository.OrderRepository;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Stage 5c: AI proposes, a human approves or rejects, Java executes. The agent itself is mocked. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:actions-test;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class ProposedActionsTest {

    @Autowired MockMvc mvc;
    @Autowired CustomerRepository customers;
    @Autowired OrderRepository orders;
    @MockitoBean AiAgentClient agentClient;

    String orderNumber;
    long ticketId;
    long agentId;

    @BeforeEach
    void ticketWithDeliveredOrder() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Customer c = customers.save(new Customer("Action Test", "action-" + suffix + "@example.com",
                CustomerTier.STANDARD, Instant.now()));
        orderNumber = "ORD-T-" + suffix;
        orders.save(new CustomerOrder(orderNumber, c, "Mechanical keyboard", new BigDecimal("89.50"),
                OrderStatus.DELIVERED, "1ZTEST", Instant.now()));

        ticketId = idOf(postJson("/api/tickets", """
                {"customerId": %d, "orderNumber": "%s", "subject": "Broken keys", "description": "Keys dead"}
                """.formatted(c.getId(), orderNumber)));
        agentId = ((Number) JsonPath.read(mvc.perform(get("/api/agents")).andReturn().getResponse()
                .getContentAsString(), "$[0].id")).longValue();
    }

    @Test
    void draftCreatesPendingProposalsWithoutDuplicates() throws Exception {
        agentSuggests("REFUND", "NO_ACTION", "MADE_UP_ACTION");

        mvc.perform(post("/api/tickets/{id}/ai-draft", ticketId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.proposedActions", hasSize(1)))
                .andExpect(jsonPath("$.proposedActions[0].type").value("REFUND"))
                .andExpect(jsonPath("$.proposedActions[0].status").value("PENDING"));

        // drafting again doesn't add a second pending REFUND
        mvc.perform(post("/api/tickets/{id}/ai-draft", ticketId))
                .andExpect(jsonPath("$.proposedActions", hasSize(0)));

        mvc.perform(get("/api/tickets/{id}/actions", ticketId)).andExpect(jsonPath("$", hasSize(1)));
        mvc.perform(get("/api/actions")).andExpect(jsonPath("$[*].ticketId", hasItem((int) ticketId)));
    }

    @Test
    void approvingARefundExecutesItOnce() throws Exception {
        long actionId = proposeOne("REFUND");

        decide(actionId, "approve")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXECUTED"))
                .andExpect(jsonPath("$.decidedById").value((int) agentId))
                .andExpect(jsonPath("$.executionResult", containsString("refunded")));

        mvc.perform(get("/api/orders/{n}", orderNumber)).andExpect(jsonPath("$.status").value("REFUNDED"));
        mvc.perform(get("/api/tickets/{id}", ticketId))
                .andExpect(jsonPath("$.comments[*].body", hasItem(containsString("Approved AI-proposed REFUND"))));

        decide(actionId, "approve")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACTION_ALREADY_DECIDED"));
    }

    @Test
    void rejectingChangesNothing() throws Exception {
        long actionId = proposeOne("REFUND");

        decide(actionId, "reject").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REJECTED"));
        mvc.perform(get("/api/orders/{n}", orderNumber)).andExpect(jsonPath("$.status").value("DELIVERED"));
    }

    @Test
    void approvingAReplacementCreatesANewOrder() throws Exception {
        long actionId = proposeOne("REPLACEMENT");

        decide(actionId, "approve").andExpect(jsonPath("$.status").value("EXECUTED"));
        mvc.perform(get("/api/orders/{n}", orderNumber + "-R1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PLACED"))
                .andExpect(jsonPath("$.productName").value("Mechanical keyboard"));
    }

    @Test
    void approvingEscalateRaisesPriority() throws Exception {
        long actionId = proposeOne("ESCALATE");

        decide(actionId, "approve").andExpect(jsonPath("$.executionResult").value("priority raised MEDIUM -> HIGH"));
        mvc.perform(get("/api/tickets/{id}", ticketId)).andExpect(jsonPath("$.priority").value("HIGH"));
    }

    @Test
    void requestMoreInfoOnAnOpenTicketAssignsTheApproverFirst() throws Exception {
        long actionId = proposeOne("REQUEST_MORE_INFO");

        decide(actionId, "approve")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.executionResult", startsWith("assigned to ")))
                .andExpect(jsonPath("$.executionResult", endsWith("ticket moved to WAITING_ON_CUSTOMER")));
        mvc.perform(get("/api/tickets/{id}", ticketId))
                .andExpect(jsonPath("$.status").value("WAITING_ON_CUSTOMER"))
                .andExpect(jsonPath("$.assignee.id").value((int) agentId));
    }

    @Test
    void businessRuleFailureRollsBackAndKeepsTheActionPending() throws Exception {
        decide(proposeOne("REFUND"), "approve").andExpect(status().isOk());
        long secondRefund = proposeOne("REFUND"); // the first is no longer PENDING, so a new one is created

        decide(secondRefund, "approve")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_ALREADY_REFUNDED"));
        mvc.perform(get("/api/tickets/{id}/actions", ticketId))
                .andExpect(jsonPath("$[?(@.id == %d)].status".formatted(secondRefund), contains("PENDING")));
    }

    @Test
    void unknownAgentIs404() throws Exception {
        long actionId = proposeOne("REFUND");
        postJson("/api/actions/" + actionId + "/approve", "{\"agentId\": 999999}").andExpect(status().isNotFound());
    }

    // ---- helpers ------------------------------------------------------------

    private long proposeOne(String type) throws Exception {
        agentSuggests(type);
        String body = mvc.perform(post("/api/tickets/{id}/ai-draft", ticketId)).andReturn().getResponse()
                .getContentAsString();
        return ((Number) JsonPath.read(body, "$.proposedActions[0].id")).longValue();
    }

    private void agentSuggests(String... actions) {
        when(agentClient.draftReply(anyLong())).thenReturn(new AgentDraftResult(ticketId,
                "Hi, sorry about that. Customer Support Team", "note", List.of(actions), 0.8, 2, List.of(),
                "fake", "fake-model", 10, null));
    }

    private ResultActions decide(long actionId, String decision) throws Exception {
        return postJson("/api/actions/" + actionId + "/" + decision,
                "{\"agentId\": %d, \"note\": \"checked with warehouse\"}".formatted(agentId));
    }

    private ResultActions postJson(String url, String json) throws Exception {
        return mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private static long idOf(ResultActions result) throws Exception {
        return ((Number) JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.id")).longValue();
    }
}
