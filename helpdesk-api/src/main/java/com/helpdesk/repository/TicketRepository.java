package com.helpdesk.repository;

import com.helpdesk.domain.Ticket;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface TicketRepository extends JpaRepository<Ticket, Long>, JpaSpecificationExecutor<Ticket> {

    /** Fetch the associations shown in list views in one query (avoids N+1). */
    @Override
    @EntityGraph(attributePaths = {"customer", "assignee", "relatedOrder"})
    Page<Ticket> findAll(Specification<Ticket> spec, Pageable pageable);

    @EntityGraph(attributePaths = {"customer", "assignee", "relatedOrder"})
    Optional<Ticket> findWithDetailsById(Long id);
}
