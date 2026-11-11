package com.tracedesk.web.dto;

import com.tracedesk.domain.enums.TicketStatus;
import java.time.OffsetDateTime;

public record TicketCreatedResponse (
    Long id,
    String ticketNumber,
    TicketStatus status,
    OffsetDateTime createdAt
){
}

