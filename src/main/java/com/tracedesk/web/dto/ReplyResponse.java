package com.tracedesk.web.dto;

import java.time.OffsetDateTime;

public record ReplyResponse (
        Long id,
        String authorUsername,
        String authorFullName,
        String message,
        OffsetDateTime createdAt
){
}
