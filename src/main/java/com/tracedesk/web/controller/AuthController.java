package com.tracedesk.web.controller;

import com.tracedesk.config.AppUserPrincipal;
import com.tracedesk.domain.entity.User;
import com.tracedesk.repository.UserRepository;
import com.tracedesk.web.dto.DepartmentResponse;
import com.tracedesk.web.dto.PositionResponse;
import com.tracedesk.web.dto.RegionResponse;
import com.tracedesk.web.dto.UserProfileResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
@Tag(name = "Authentication", description = "Аутентификация по ИИН и профиль текущего пользователя")
public class AuthController {

    private final UserRepository userRepository;

    public AuthController(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Operation(
            summary = "Профиль текущего авторизованного пользователя",
            description = "Возвращает полную информацию об авторизованном пользователе (id, iin, email, fullName, firstName, lastName, role, status, region, department, position)."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Полный профиль авторизованного пользователя"),
            @ApiResponse(responseCode = "401", description = "Неверный ИИН или пароль (UNAUTHORIZED)"),
            @ApiResponse(responseCode = "403", description = "Заявка на рассмотрении (ACCOUNT_PENDING_APPROVAL) или доступ заблокирован (ACCOUNT_BLOCKED)"),
            @ApiResponse(responseCode = "423", description = "Аккаунт временно заблокирован (на 5 мин после 3 ошибок, далее на 1 час после каждых 2 ошибок) (ACCOUNT_LOCKED)")
    })
    @GetMapping("/me")
    public ResponseEntity<UserProfileResponse> getCurrentUser(@AuthenticationPrincipal AppUserPrincipal principal) {
        User user = userRepository.findWithProfileById(principal.getUser().getId())
                .orElse(principal.getUser());

        RegionResponse region = user.getRegion() != null
                ? new RegionResponse(user.getRegion().getId(), user.getRegion().getName())
                : null;
        DepartmentResponse department = user.getDepartment() != null
                ? new DepartmentResponse(user.getDepartment().getId(), user.getDepartment().getName())
                : null;
        PositionResponse position = user.getPosition() != null
                ? new PositionResponse(user.getPosition().getId(), user.getPosition().getName())
                : null;

        UserProfileResponse response = new UserProfileResponse(
                user.getId(),
                user.getIin(),
                user.getEmail(),
                user.getFullName(),
                user.getFirstName(),
                user.getLastName(),
                user.getRole(),
                user.getStatus(),
                region,
                department,
                position
        );

        return ResponseEntity.ok(response);
    }
}
