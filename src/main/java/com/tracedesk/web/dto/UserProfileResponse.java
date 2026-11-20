package com.tracedesk.web.dto;

import com.tracedesk.domain.enums.Role;
import com.tracedesk.domain.enums.UserStatus;

public record UserProfileResponse(
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
        PositionResponse position
) {
}
