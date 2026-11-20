package com.tracedesk.web.dto;

public record PasswordResetConfirmRequest(
        String iin,
        String code,
        String newPassword,
        String confirmPassword
) {
    public PasswordResetConfirmRequest(String iin, String code, String newPassword) {
        this(iin, code, newPassword, newPassword);
    }
}
