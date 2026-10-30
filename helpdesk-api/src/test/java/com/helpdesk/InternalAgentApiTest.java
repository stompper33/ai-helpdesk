package com.helpdesk;

import com.helpdesk.ai.AiServiceConstants.Headers;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Stage 5c: the agent's internal API is authenticated and scoped to one ticket's customer. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:internal-api-test;DB_CLOSE_DELAY=-1",
        "helpdesk.internal-api.key=test-service-key"})
@AutoConfigureMockMvc
class InternalAgentApiTest {

    static final String KEY = "test-service-key";

    @Autowired MockMvc mvc;
    @Autowired CustomerRepository customers;
    @Autowired OrderRepository orders;

    long ticketId;
    String ownOrder;
    String otherCustomersOrder;

    @BeforeEach
    void twoCustomers() throws Exception {
        String s = UUID.randomUUID().toString().substring(0, 8);
        Customer bob = customers.save(new Customer("Bob", "bob-" + s + "@example.com", CustomerTier.STANDARD, Instant.now()));
        Customer alice = customers.save(new Customer("Alice", "alice-" + s + "@example.com", CustomerTier.PREMIUM, Instant.now()));
        ownOrder = order("ORD-B-" + s, bob);
        otherCustomersOrder = order("ORD-A-" + s, alice);

        String created = mvc.perform(post("/api/tickets").contentType(MediaType.APPLICATION_JSON).content("""
                        {"customerId": %d, "orderNumber": "%s", "subject": "Broken", "description": "Dead keys"}
                        """.formatted(bob.getId(), ownOrder)))
                .andReturn().getResponse().getContentAsString();
        ticketId = ((Number) JsonPath.read(created, "$.id")).longValue();
        // an older ticket for history
        mvc.perform(post("/api/tickets").contentType(MediaType.APPLICATION_JSON).content("""
                {"customerId": %d, "subject": "Earlier question", "description": "Hi"}
                """.formatted(bob.getId())));
    }

    @Test
    void missingOrWrongKeyIsRejected() throws Exception {
        mvc.perform(get("/internal/agent/tickets/{id}", ticketId))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_SERVICE_KEY"));
        mvc.perform(get("/internal/agent/tickets/{id}", ticketId).header(Headers.API_KEY_HEADER, "nope"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void publicApiDoesNotNeedTheKey() throws Exception {
        mvc.perform(get("/api/tickets/{id}", ticketId)).andExpect(status().isOk());
    }

    @Test
    void ticketAndOrdersAreScopedToTheTicketsCustomer() throws Exception {
        mvc.perform(withKey(get("/internal/agent/tickets/{id}", ticketId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customer.name").value("Bob"));

        mvc.perform(withKey(get("/internal/agent/tickets/{id}/orders", ticketId)))
                .andExpect(jsonPath("$[*].orderNumber", contains(ownOrder)));

        mvc.perform(withKey(get("/internal/agent/tickets/{id}/orders/{n}", ticketId, ownOrder)))
                .andExpect(status().isOk());
        mvc.perform(withKey(get("/internal/agent/tickets/{id}/orders/{n}", ticketId, otherCustomersOrder)))
                .andExpect(status().isNotFound());
    }

    @Test
    void historyListsOtherTicketsOnly() throws Exception {
        mvc.perform(withKey(get("/internal/agent/tickets/{id}/history", ticketId)))
                .andExpect(jsonPath("$[*].subject", contains("Earlier question")))
                .andExpect(jsonPath("$[*].id", not(hasItem((int) ticketId))));
    }

    @Test
    void internalApiIsReadOnly() throws Exception {
        mvc.perform(withKey(post("/internal/agent/tickets/{id}", ticketId)))
                .andExpect(status().isMethodNotAllowed());
    }

    private MockHttpServletRequestBuilder withKey(MockHttpServletRequestBuilder request) {
        return request.header(Headers.API_KEY_HEADER, KEY);
    }

    private String order(String number, Customer c) {
        orders.save(new CustomerOrder(number, c, "Keyboard", new BigDecimal("10.00"), OrderStatus.DELIVERED, null,
                Instant.now()));
        return number;
    }
}
