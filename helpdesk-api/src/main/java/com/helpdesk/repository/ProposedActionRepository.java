package com.helpdesk.repository;

import com.helpdesk.domain.ProposedAction;
import com.helpdesk.domain.ProposedAction.Status;
import com.helpdesk.domain.ProposedAction.Type;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProposedActionRepository extends JpaRepository<ProposedAction, Long> {

    @EntityGraph(attributePaths = {"ticket", "decidedBy"})
    List<ProposedAction> findByStatusOrderByCreatedAtAsc(Status status);

    @EntityGraph(attributePaths = {"ticket", "decidedBy"})
    List<ProposedAction> findByTicketIdOrderByCreatedAtAsc(Long ticketId);

    @EntityGraph(attributePaths = {"ticket", "decidedBy"})
    Optional<ProposedAction> findWithTicketById(Long id);

    boolean existsByTicketIdAndTypeAndStatus(Long ticketId, Type type, Status status);
}
