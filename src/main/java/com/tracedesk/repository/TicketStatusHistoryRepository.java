package com.tracedesk.repository;

import com.tracedesk.domain.entity.TicketStatusHistory;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TicketStatusHistoryRepository extends JpaRepository<TicketStatusHistory, Long> {

    @EntityGraph(attributePaths = {"changedBy"})
    List<TicketStatusHistory> findByTicketIdOrderByChangedAtAscIdAsc(Long ticketId);
}
