package com.tracedesk.web.dto;

import com.tracedesk.domain.enums.Role;

public record UpdateUserProfileRequest(
        String firstName,
        String lastName,
        Role role,
        String regionId,
        Short departmentId,
        Short positionId
) {
}
