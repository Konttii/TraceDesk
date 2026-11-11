package com.tracedesk.web.dto;

import com.tracedesk.domain.enums.TicketStatus;
import java.time.OffsetDateTime;
import java.util.List;

public record TicketDetailsResponse(
        Long id,
        String ticketNumber,
        String title,
        String description,
        TicketStatus status,
        String authorUsername,
        String authorFullName,
        String assigneeUsername,
        String assigneeFullName,
        String attachmentName,
        String attachmentType,
        Long attachmentSize,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        List<ReplyResponse> replies,
        List<StatusHistoryResponse> history
) {
}