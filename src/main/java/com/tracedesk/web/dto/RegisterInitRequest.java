package com.tracedesk.web.dto;

import com.tracedesk.domain.enums.Role;

public record RegisterInitRequest(
        String firstName,
        String lastName,
        String iin,
        String email,
        String password,
        Role role,
        String regionId,
        Short departmentId,
        Short positionId
) {
}
