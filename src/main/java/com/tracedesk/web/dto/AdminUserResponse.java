package com.tracedesk.web.dto;

import com.tracedesk.domain.enums.Role;
import com.tracedesk.domain.enums.UserStatus;

import java.time.OffsetDateTime;

public record AdminUserResponse(
        Long id,
        String iin,
        String email,
        String fullName,
        String firstName,
        String lastName,
        Role role,
        UserStatus status,
        RegionResponse region,
        DepartmentResponse department,
        PositionResponse position,
        int failedLoginAttempts,
        OffsetDateTime lockedUntil,
        OffsetDateTime createdAt
) {
}
