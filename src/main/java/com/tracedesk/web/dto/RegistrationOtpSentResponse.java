package com.tracedesk.web.dto;

import com.tracedesk.domain.enums.PasswordStrength;

public record RegistrationOtpSentResponse(
        String email,
        String message,
        int cooldownSeconds,
        int remainingResends,
        PasswordStrength passwordStrength
) {
}
