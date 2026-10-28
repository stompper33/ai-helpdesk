package com.helpdesk.service;

import com.helpdesk.domain.Customer;
import com.helpdesk.domain.Enums.CustomerTier;
import com.helpdesk.exception.BusinessRuleException;
import com.helpdesk.exception.NotFoundException;
import com.helpdesk.repository.CustomerRepository;
import com.helpdesk.repository.OrderRepository;
import com.helpdesk.repository.SupportAgentRepository;
import com.helpdesk.web.dto.Requests.CreateCustomerRequest;
import com.helpdesk.web.dto.Responses.AgentResponse;
import com.helpdesk.web.dto.Responses.CustomerResponse;
import com.helpdesk.web.dto.Responses.OrderResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;

/** Customers, their orders, and the support-agent directory. */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class CustomerService {

    private final CustomerRepository customerRepository;
    private final OrderRepository orderRepository;
    private final SupportAgentRepository agentRepository;
    private final Clock clock;

    public List<CustomerResponse> listCustomers() {
        return customerRepository.findAll(Sort.by("id")).stream().map(CustomerResponse::from).toList();
    }

    public CustomerResponse getCustomer(Long id) {
        return CustomerResponse.from(findCustomer(id));
    }

    @Transactional
    public CustomerResponse createCustomer(CreateCustomerRequest req) {
        if (customerRepository.existsByEmailIgnoreCase(req.email())) {
            throw new BusinessRuleException("EMAIL_TAKEN", "A customer with email " + req.email() + " already exists");
        }
        CustomerTier tier = req.tier() != null ? req.tier() : CustomerTier.STANDARD;
        Customer saved = customerRepository.save(new Customer(req.name(), req.email(), tier, clock.instant()));
        return CustomerResponse.from(saved);
    }

    public List<OrderResponse> listOrdersForCustomer(Long customerId) {
        findCustomer(customerId);
        return orderRepository.findByCustomerIdOrderByOrderedAtDesc(customerId).stream().map(OrderResponse::from).toList();
    }

    public OrderResponse getOrderByNumber(String orderNumber) {
        return orderRepository.findByOrderNumberIgnoreCase(orderNumber)
                .map(OrderResponse::from)
                .orElseThrow(() -> new NotFoundException("Order", orderNumber));
    }

    public List<AgentResponse> listAgents() {
        return agentRepository.findAll(Sort.by("id")).stream().map(AgentResponse::from).toList();
    }

    private Customer findCustomer(Long id) {
        return customerRepository.findById(id).orElseThrow(() -> new NotFoundException("Customer", id));
    }
}
