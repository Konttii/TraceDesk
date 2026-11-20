package com.tracedesk.web.dto;

public record VerifyOtpRequest(
        String email,
        String code
) {
}
