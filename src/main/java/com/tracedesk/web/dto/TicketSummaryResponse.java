package com.tracedesk.web.dto;

import com.tracedesk.domain.enums.TicketStatus;

import java.time.OffsetDateTime;

public record TicketSummaryResponse(
        Long id,
        String ticketNumber,
        String title,
        TicketStatus status,
        String authorUsername,
        String authorFullName,
        String assigneeUsername,
        String assigneeFullName,
        boolean hashAttachment,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
