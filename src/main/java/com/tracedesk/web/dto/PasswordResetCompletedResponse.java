package com.tracedesk.web.dto;

public record PasswordResetCompletedResponse(
        String iin,
        String message
) {
}
