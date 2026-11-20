package com.tracedesk.web.controller;

import com.tracedesk.config.AppUserPrincipal;
import com.tracedesk.domain.enums.Role;
import com.tracedesk.domain.enums.UserStatus;
import com.tracedesk.service.AdminUserService;
import com.tracedesk.web.dto.AdminUserResponse;
import com.tracedesk.web.dto.UpdateUserProfileRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/admin")
@Tag(name = "Admin Panel", description = "Управление заявками на регистрацию и учётными записями сотрудников (только ADMIN)")
public class AdminController {

    private final AdminUserService adminUserService;

    public AdminController(AdminUserService adminUserService) {
        this.adminUserService = adminUserService;
    }

    @Operation(
            summary = "Список заявок на регистрацию (PENDING_APPROVAL)",
            description = "Возвращает все заявки пользователей, ожидающие подтверждения администратором."
    )
    @GetMapping("/registrations")
    public ResponseEntity<List<AdminUserResponse>> listPendingRegistrations() {
        return ResponseEntity.ok(adminUserService.getPendingRegistrations());
    }

    @Operation(
            summary = "Список сотрудников (следователи и саппорты)",
            description = "Возвращает всех следователей (INVESTIGATOR) и операторов (SUPPORT) с опциональной фильтрацией по роли, региону и статусу."
    )
    @GetMapping("/users")
    public ResponseEntity<List<AdminUserResponse>> listUsers(
            @Parameter(description = "Фильтр по роли (INVESTIGATOR / SUPPORT)") @RequestParam(value = "role", required = false) Role role,
            @Parameter(description = "Фильтр по коду региона ('01'..'20')") @RequestParam(value = "regionId", required = false) String regionId,
            @Parameter(description = "Фильтр по статусу (PENDING_APPROVAL / ACTIVE / REJECTED / BLOCKED)") @RequestParam(value = "status", required = false) UserStatus status
    ) {
        return ResponseEntity.ok(adminUserService.getUsers(role, regionId, status));
    }

    @Operation(
            summary = "Детальная карточка пользователя",
            description = "Возвращает полную информацию о пользователе, включая данные регистрации и справочники."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Карточка пользователя"),
            @ApiResponse(responseCode = "404", description = "Пользователь не найден (USER_NOT_FOUND)")
    })
    @GetMapping("/users/{id}")
    public ResponseEntity<AdminUserResponse> getUserDetails(@PathVariable("id") Long id) {
        return ResponseEntity.ok(adminUserService.getUserDetails(id));
    }

    @Operation(
            summary = "Одобрить заявку на регистрацию",
            description = "Переводит пользователя из PENDING_APPROVAL в ACTIVE и после коммита транзакции отправляет уведомление через RabbitMQ."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Заявка одобрена"),
            @ApiResponse(responseCode = "404", description = "Пользователь не найден"),
            @ApiResponse(responseCode = "409", description = "Пользователь не находится в статусе PENDING_APPROVAL")
    })
    @PostMapping("/registrations/{id}/approve")
    public ResponseEntity<AdminUserResponse> approveRegistration(@PathVariable("id") Long id) {
        return ResponseEntity.ok(adminUserService.approveRegistration(id));
    }

    @Operation(
            summary = "Отклонить заявку на регистрацию",
            description = "Переводит пользователя в статус REJECTED и после коммита транзакции отправляет письмо об отказе через RabbitMQ."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Заявка отклонена"),
            @ApiResponse(responseCode = "404", description = "Пользователь не найден"),
            @ApiResponse(responseCode = "409", description = "Некорректный статус (INVALID_USER_STATUS), запрет действия над собой (SELF_ACTION_FORBIDDEN) или защита последнего администратора (LAST_ADMIN_PROTECTION)")
    })
    @PostMapping("/registrations/{id}/reject")
    public ResponseEntity<AdminUserResponse> rejectRegistration(
            @PathVariable("id") Long id,
            @AuthenticationPrincipal AppUserPrincipal principal
    ) {
        return ResponseEntity.ok(adminUserService.rejectRegistration(id, principal != null ? principal.getUser() : null));
    }

    @Operation(
            summary = "Редактировать профиль сотрудника",
            description = "Обновляет ФИО, роль, регион, следственное управление и должность сотрудника."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Профиль обновлён"),
            @ApiResponse(responseCode = "400", description = "Ошибка валидации (VALIDATION_ERROR)"),
            @ApiResponse(responseCode = "404", description = "Пользователь не найден (USER_NOT_FOUND)"),
            @ApiResponse(responseCode = "409", description = "Запрет понижения собственной роли (SELF_ACTION_FORBIDDEN) или последнего активного администратора (LAST_ADMIN_PROTECTION)")
    })
    @PutMapping("/users/{id}")
    public ResponseEntity<AdminUserResponse> updateUserProfile(
            @PathVariable("id") Long id,
            @RequestBody UpdateUserProfileRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal
    ) {
        return ResponseEntity.ok(adminUserService.updateUserProfile(id, request, principal != null ? principal.getUser() : null));
    }

    @Operation(
            summary = "Заблокировать пользователя",
            description = "Переводит учётную запись пользователя в статус BLOCKED."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Пользователь заблокирован"),
            @ApiResponse(responseCode = "404", description = "Пользователь не найден (USER_NOT_FOUND)"),
            @ApiResponse(responseCode = "409", description = "Запрет самоблокировки (SELF_ACTION_FORBIDDEN) или блокировки последнего активного администратора (LAST_ADMIN_PROTECTION)")
    })
    @PostMapping("/users/{id}/block")
    public ResponseEntity<AdminUserResponse> blockUser(
            @PathVariable("id") Long id,
            @AuthenticationPrincipal AppUserPrincipal principal
    ) {
        return ResponseEntity.ok(adminUserService.blockUser(id, principal != null ? principal.getUser() : null));
    }

    @Operation(
            summary = "Разблокировать пользователя",
            description = "Переводит пользователя в статус ACTIVE и сбрасывает блокировку за ошибки пароля (lockedUntil = null, failedLoginAttempts = 0)."
    )
    @PostMapping("/users/{id}/unblock")
    public ResponseEntity<AdminUserResponse> unblockUser(@PathVariable("id") Long id) {
        return ResponseEntity.ok(adminUserService.unblockUser(id));
    }
}
