package com.tracedesk.web.dto;

import com.tracedesk.domain.enums.Role;

public record RegistrationDraft(
        String firstName,
        String lastName,
        String iin,
        String email,
        String passwordHash,
        Role role,
        String regionId,
        Short departmentId,
        Short positionId,
        String otpCode,
        int verifyAttempts,
        int resendCount
) {
}
