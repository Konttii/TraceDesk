package com.tracedesk.web.dto;

public record PasswordResetVerifyOtpRequest(
        String iin,
        String code
) {
}
