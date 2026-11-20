package com.tracedesk.web.controller;

import com.tracedesk.service.RegistrationService;
import com.tracedesk.web.dto.RegisterInitRequest;
import com.tracedesk.web.dto.RegistrationCompletedResponse;
import com.tracedesk.web.dto.RegistrationOtpSentResponse;
import com.tracedesk.web.dto.ResendOtpRequest;
import com.tracedesk.web.dto.VerifyOtpRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth/register")
@Tag(name = "Registration", description = "Подача заявки на регистрацию с проверкой почты по 6-значному OTP-коду (Redis + RabbitMQ)")
public class AuthRegistrationController {

    private final RegistrationService registrationService;

    public AuthRegistrationController(RegistrationService registrationService) {
        this.registrationService = registrationService;
    }

    @Operation(
            summary = "Шаг 1: Инициализация регистрации и отправка 6-значного OTP-кода на почту",
            description = "Валидирует ИИН, Email, пароль и справочники, сохраняет черновик в Redis (TTL 10 мин) и публикует задачу на отправку письма в RabbitMQ."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Черновик сохранён в Redis, OTP-код отправлен в очередь писем"),
            @ApiResponse(responseCode = "400", description = "Некорректные данные анкеты (ИИН, Email, пароль или справочники)"),
            @ApiResponse(responseCode = "409", description = "ИИН или Email уже зарегистрированы (IIN_ALREADY_EXISTS / EMAIL_ALREADY_EXISTS)"),
            @ApiResponse(responseCode = "429", description = "Почта временно заблокирована (EMAIL_VERIFICATION_BLOCKED)")
    })
    @PostMapping("/init")
    public ResponseEntity<RegistrationOtpSentResponse> initRegistration(@RequestBody RegisterInitRequest request) {
        return ResponseEntity.ok(registrationService.initRegistration(request));
    }

    @Operation(
            summary = "Шаг 2: Подтверждение почты 6-значным OTP-кодом",
            description = "При верном коде создаёт пользователя в PostgreSQL со статусом PENDING_APPROVAL (201 Created). При неверном коде считает попытки (макс. 5)."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Почта подтверждена, заявка создана в статусе PENDING_APPROVAL"),
            @ApiResponse(responseCode = "400", description = "Неверный код (INVALID_OTP_CODE) или 5 неверных попыток подряд (OTP_ATTEMPTS_EXCEEDED)"),
            @ApiResponse(responseCode = "409", description = "ИИН или Email уже заняты в БД"),
            @ApiResponse(responseCode = "429", description = "Исчерпан лимит попыток и переотправок — бан на 1 час (EMAIL_VERIFICATION_BLOCKED)")
    })
    @PostMapping("/verify-otp")
    public ResponseEntity<RegistrationCompletedResponse> verifyOtp(@RequestBody VerifyOtpRequest request) {
        RegistrationCompletedResponse response = registrationService.verifyOtp(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @Operation(
            summary = "Повторная отправка 6-значного OTP-кода (кулдаун 60 сек, макс. 3 переотправки)",
            description = "Сбрасывает счётчик verifyAttempts, генерирует новый 6-значный код и отправляет письмо через RabbitMQ."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Новый код отправлен"),
            @ApiResponse(responseCode = "400", description = "Черновик регистрации не найден или истёк"),
            @ApiResponse(responseCode = "429", description = "Кулдаун 60 сек (OTP_COOLDOWN_ACTIVE) или исчерпан лимит 3 переотправок (EMAIL_VERIFICATION_BLOCKED)")
    })
    @PostMapping("/resend-otp")
    public ResponseEntity<RegistrationOtpSentResponse> resendOtp(@RequestBody ResendOtpRequest request) {
        return ResponseEntity.ok(registrationService.resendOtp(request));
    }
}
