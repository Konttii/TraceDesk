package com.tracedesk.repository;

import com.tracedesk.domain.entity.TicketReply;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TicketReplyRepository extends JpaRepository<TicketReply, Long> {

    @EntityGraph(attributePaths = {"author"})
    List<TicketReply> findByTicketIdOrderByCreatedAtAscIdAsc(Long ticketid);

    boolean existsByTicketId(Long ticketid);
}
