package com.tracedesk.web.dto;

import com.tracedesk.domain.entity.Ticket;
import com.tracedesk.domain.enums.TicketStatus;
import java.time.OffsetDateTime;

public record StatusHistoryResponse (
        Long id,
        TicketStatus oldStatus,
        TicketStatus newStatus,
        String changedByUsername,
        String changedByFullName,
        OffsetDateTime changedAt
){
}
