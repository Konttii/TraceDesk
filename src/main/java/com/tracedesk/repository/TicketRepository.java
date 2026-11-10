package com.tracedesk.repository;

import com.tracedesk.domain.entity.Ticket;
import com.tracedesk.domain.enums.TicketStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface TicketRepository extends JpaRepository<Ticket, Long> {

    @Query(value = "SELECT nextval('ticket_number_seq')", nativeQuery = true)
    Long getNextTicketSequenceValue();

    @Override
    @EntityGraph(attributePaths = {"author", "assignee"})
    Page<Ticket> findAll(Pageable pageable);

    @EntityGraph(attributePaths = {"author", "assignee"})
    Page<Ticket> findByStatus(TicketStatus status, Pageable pageable);

    @EntityGraph(attributePaths = {"author", "assignee"})
    Page<Ticket> findByAuthorId(Long authorId, Pageable pageable);

    @EntityGraph(attributePaths = {"author", "assignee"})
    Page<Ticket> findByAuthorIdAndStatus(Long authorId, TicketStatus status, Pageable pageable);

    @EntityGraph(attributePaths = {"author", "assignee"})
    Optional<Ticket> findWithDetailsById(Long id);
}