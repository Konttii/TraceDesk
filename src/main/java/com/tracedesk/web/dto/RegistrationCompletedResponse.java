package com.tracedesk.web.dto;

import com.tracedesk.domain.enums.UserStatus;

public record RegistrationCompletedResponse(
        Long userId,
        String iin,
        String email,
        UserStatus status,
        String message
) {
}
