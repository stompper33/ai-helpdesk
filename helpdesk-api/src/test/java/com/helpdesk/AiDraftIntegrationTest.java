package com.helpdesk;

import com.helpdesk.ai.AgentDtos.AgentDraftResult;
import com.helpdesk.ai.AgentDtos.ToolCallTrace;
import com.helpdesk.ai.AiAgentClient;
import com.helpdesk.ai.AiDtos.Usage;
import com.helpdesk.ai.AiServiceException;
import com.jayway.jsonpath.JsonPath;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Java side of stage 3. The agent client is mocked, so ai-service does not need to run. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:ai-draft-test;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class AiDraftIntegrationTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    AiAgentClient agentClient;

    long ticketId;

    @BeforeEach
    void createTicket() throws Exception {
        String customer = mvc.perform(post("/api/customers").contentType(MediaType.APPLICATION_JSON).content("""
                        {"name": "Draft Test", "email": "draft-%s@example.com"}
                        """.formatted(UUID.randomUUID())))
                .andReturn().getResponse().getContentAsString();
        long customerId = ((Number) JsonPath.read(customer, "$.id")).longValue();

        String ticket = mvc.perform(post("/api/tickets").contentType(MediaType.APPLICATION_JSON).content("""
                        {"customerId": %d, "subject": "Broken keys", "description": "Two keys don't work"}
                        """.formatted(customerId)))
                .andReturn().getResponse().getContentAsString();
        ticketId = ((Number) JsonPath.read(ticket, "$.id")).longValue();
    }

    @Test
    void draftIsStoredAsInternalAiCommentAndTicketIsOtherwiseUnchanged() throws Exception {
        when(agentClient.draftReply(anyLong())).thenReturn(draft());

        mvc.perform(post("/api/tickets/{id}/ai-draft", ticketId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.draft.customerReply", Matchers.startsWith("Hi Draft")))
                .andExpect(jsonPath("$.ticket.status").value("OPEN"))
                .andExpect(jsonPath("$.ticket.category").value(nullValue()))
                .andExpect(jsonPath("$.ticket.comments", hasSize(1))) // a new ticket has no other comments
                .andExpect(jsonPath("$.ticket.comments[0].authorType").value("AI"))
                .andExpect(jsonPath("$.ticket.comments[0].internal").value(true))
                .andExpect(jsonPath("$.ticket.comments[0].body", allOf(
                        containsString("AI DRAFT REPLY"),
                        containsString("Hi Draft, sorry about the keyboard"),
                        containsString("Suggested actions: REPLACEMENT"),
                        containsString("get_order(ORD-1)"),
                        not(containsString("submit_reply")))));

        // The customer-facing view must not show the draft
        mvc.perform(get("/api/tickets/{id}/comments", ticketId).param("includeInternal", "false"))
                .andExpect(jsonPath("$[*].authorType", not(hasItem("AI"))));
    }

    @Test
    void aiServiceDownReturns503() throws Exception {
        when(agentClient.draftReply(anyLong())).thenThrow(new AiServiceException(
                "AI_SERVICE_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE, "timed out", null));

        mvc.perform(post("/api/tickets/{id}/ai-draft", ticketId))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("AI_SERVICE_UNAVAILABLE"));
    }

    @Test
    void closedTicketIsRejectedWithoutCallingAgent() throws Exception {
        mvc.perform(post("/api/tickets/{id}/status", ticketId).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\": \"CLOSED\"}")).andExpect(status().isOk());

        mvc.perform(post("/api/tickets/{id}/ai-draft", ticketId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TICKET_CLOSED"));
        verifyNoInteractions(agentClient);
    }

    @Test
    void unknownTicketIs404WithoutCallingAgent() throws Exception {
        mvc.perform(post("/api/tickets/{id}/ai-draft", 999999))
                .andExpect(status().isNotFound());
        verifyNoInteractions(agentClient);
    }

    private AgentDraftResult draft() {
        return new AgentDraftResult(ticketId, "Hi Draft, sorry about the keyboard. Customer Support Team",
                "Order delivered 4 days ago; within the 30-day window.", List.of("REPLACEMENT"), 0.82, 3,
                List.of(new ToolCallTrace("get_order", Map.of("order_number", "ORD-1"), true, 12),
                        new ToolCallTrace("get_support_policy", Map.of("topic", "returns"), true, 1),
                        new ToolCallTrace("submit_reply", Map.of(), true, 0)),
                "fake", "fake-model", 1500, new Usage(900, 200));
    }
}
