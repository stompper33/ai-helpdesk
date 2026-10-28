package com.helpdesk.repository;

import com.helpdesk.domain.CustomerOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface OrderRepository extends JpaRepository<CustomerOrder, Long> {

    Optional<CustomerOrder> findByOrderNumberIgnoreCase(String orderNumber);

    List<CustomerOrder> findByCustomerIdOrderByOrderedAtDesc(Long customerId);
}
