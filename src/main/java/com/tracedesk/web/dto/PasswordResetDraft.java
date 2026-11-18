package com.tracedesk.web.dto;

public record PasswordResetDraft(
        String iin,
        String otpCode,
        int verifyAttempts,
        int resendCount
) {
}
