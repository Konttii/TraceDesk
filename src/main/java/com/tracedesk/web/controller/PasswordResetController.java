package com.tracedesk.web.controller;

import com.tracedesk.service.PasswordResetService;
import com.tracedesk.web.dto.PasswordResetCompletedResponse;
import com.tracedesk.web.dto.PasswordResetConfirmRequest;
import com.tracedesk.web.dto.PasswordResetInitRequest;
import com.tracedesk.web.dto.PasswordResetOtpSentResponse;
import com.tracedesk.web.dto.PasswordResetResendRequest;
import com.tracedesk.web.dto.PasswordResetVerifyOtpRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth/password-reset")
@Tag(name = "Password Reset", description = "Восстановление забытого пароля по ИИН через 6-значный OTP-код (Redis + RabbitMQ)")
public class PasswordResetController {

    private final PasswordResetService passwordResetService;

    public PasswordResetController(PasswordResetService passwordResetService) {
        this.passwordResetService = passwordResetService;
    }

    @Operation(
            summary = "Шаг 1: Запрос на восстановление пароля по ИИН",
            description = "Проверяет статус пользователя (ACTIVE), сохраняет 6-значный OTP-код в Redis (TTL 10 мин), отправляет письмо через RabbitMQ и возвращает замаскированный Email."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "OTP-код отправлен, возвращён maskedEmail"),
            @ApiResponse(responseCode = "400", description = "Некорректный формат ИИН"),
            @ApiResponse(responseCode = "403", description = "Учётная запись не в статусе ACTIVE"),
            @ApiResponse(responseCode = "404", description = "Пользователь с указанным ИИН не найден (USER_NOT_FOUND)"),
            @ApiResponse(responseCode = "429", description = "Активен кулдаун 60 сек или бан на 1 час (PASSWORD_RESET_BLOCKED)")
    })
    @PostMapping("/init")
    public ResponseEntity<PasswordResetOtpSentResponse> initPasswordReset(@RequestBody PasswordResetInitRequest request) {
        return ResponseEntity.ok(passwordResetService.initPasswordReset(request));
    }

    @Operation(
            summary = "Шаг 2: Повторная отправка 6-значного кода восстановления (кулдаун 60 сек, макс. 3 переотправки)",
            description = "Сбрасывает счётчик verifyAttempts, генерирует новый 6-значный код и отправляет письмо через RabbitMQ."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Новый код отправлен"),
            @ApiResponse(responseCode = "400", description = "Сессия восстановления не найдена или истекла"),
            @ApiResponse(responseCode = "429", description = "Кулдаун 60 сек (OTP_COOLDOWN_ACTIVE) или исчерпан лимит 3 переотправок (PASSWORD_RESET_BLOCKED)")
    })
    @PostMapping("/resend-otp")
    public ResponseEntity<PasswordResetOtpSentResponse> resendOtp(@RequestBody PasswordResetResendRequest request) {
        return ResponseEntity.ok(passwordResetService.resendOtp(request));
    }

    @Operation(
            summary = "Проверка 6-значного OTP-кода восстановления перед вводом нового пароля",
            description = "Проверяет 6-значный OTP-код в Redis (с подсчётом попыток макс. 5). При успехе переводит UI на шаг ввода нового пароля и его подтверждения."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Код верный, можно вводить новый пароль"),
            @ApiResponse(responseCode = "400", description = "Неверный код (INVALID_OTP_CODE) или исчерпаны 5 попыток (OTP_ATTEMPTS_EXCEEDED)"),
            @ApiResponse(responseCode = "429", description = "Превышен лимит попыток восстановления (PASSWORD_RESET_BLOCKED)")
    })
    @PostMapping("/verify-otp")
    public ResponseEntity<PasswordResetCompletedResponse> verifyOtp(@RequestBody PasswordResetVerifyOtpRequest request) {
        return ResponseEntity.ok(passwordResetService.verifyOtp(request));
    }

    @Operation(
            summary = "Шаг 3: Подтверждение OTP-кода и установка нового пароля",
            description = "Проверяет OTP-код и новый пароль (отличный от текущего), обновляет хэш пароля, сбрасывает блокировку входа (lockedUntil = null, failedLoginAttempts = 0) и отправляет уведомление через RabbitMQ."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Пароль успешно изменён, блокировка входа снята"),
            @ApiResponse(responseCode = "400", description = "Неверный код (INVALID_OTP_CODE), исчерпаны 5 попыток (OTP_ATTEMPTS_EXCEEDED), слабый пароль или совпадение со старым паролем (SAME_PASSWORD)"),
            @ApiResponse(responseCode = "429", description = "Превышен лимит попыток восстановления (PASSWORD_RESET_BLOCKED)")
    })
    @PostMapping("/confirm")
    public ResponseEntity<PasswordResetCompletedResponse> confirmPasswordReset(@RequestBody PasswordResetConfirmRequest request) {
        return ResponseEntity.ok(passwordResetService.confirmPasswordReset(request));
    }
}
