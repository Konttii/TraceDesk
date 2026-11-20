package com.tracedesk.web.dto;

public record PasswordResetOtpSentResponse(
        String maskedEmail,
        String message,
        int cooldownSeconds,
        int remainingResends
) {
}
