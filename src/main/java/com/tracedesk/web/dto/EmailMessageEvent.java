package com.tracedesk.web.dto;

import java.util.Map;

public record EmailMessageEvent(
        String toEmail,
        String subject,
        String templateName,
        Map<String, Object> variables
) {
}
