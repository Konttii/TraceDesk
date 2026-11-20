package com.tracedesk.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tracedesk.domain.entity.User;
import com.tracedesk.domain.enums.UserStatus;
import com.tracedesk.repository.UserRepository;
import com.tracedesk.web.dto.PasswordResetCompletedResponse;
import com.tracedesk.web.dto.PasswordResetConfirmRequest;
import com.tracedesk.web.dto.PasswordResetDraft;
import com.tracedesk.web.dto.PasswordResetInitRequest;
import com.tracedesk.web.dto.PasswordResetOtpSentResponse;
import com.tracedesk.web.dto.PasswordResetResendRequest;
import com.tracedesk.web.dto.PasswordResetVerifyOtpRequest;
import com.tracedesk.web.exception.ApiException;
import com.tracedesk.web.exception.ErrorCode;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

@Service
public class PasswordResetService {

    public static final String RESET_KEY_PREFIX = "pwd:reset:";
    public static final String COOLDOWN_KEY_PREFIX = "pwd:cooldown:";
    public static final String BAN_KEY_PREFIX = "pwd:ban:";

    public static final Duration RESET_TTL = Duration.ofMinutes(10);
    public static final Duration COOLDOWN_TTL = Duration.ofSeconds(60);
    public static final Duration BAN_TTL = Duration.ofHours(1);

    public static final int MAX_VERIFY_ATTEMPTS = 5;
    public static final int MAX_RESEND_COUNT = 3;

    private static final Pattern TWELVE_DIGITS_PATTERN = Pattern.compile("^\\d{12}$");
    private static final Pattern OTP_PATTERN = Pattern.compile("^\\d{6}$");

    private final StringRedisTemplate redisTemplate;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final PasswordValidator passwordValidator;
    private final EmailNotificationProducer emailNotificationProducer;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SecureRandom secureRandom = new SecureRandom();

    public PasswordResetService(
            StringRedisTemplate redisTemplate,
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            PasswordValidator passwordValidator,
            EmailNotificationProducer emailNotificationProducer
    ) {
        this.redisTemplate = redisTemplate;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.passwordValidator = passwordValidator;
        this.emailNotificationProducer = emailNotificationProducer;
    }

    @Transactional(readOnly = true)
    public PasswordResetOtpSentResponse initPasswordReset(PasswordResetInitRequest request) {
        if (request == null) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Запрос на восстановление пароля не может быть пустым");
        }

        String iin = validateIinDigits(request.iin());
        checkNotBanned(iin);

        User user = getActiveUserByIinOrThrow(iin);
        checkCooldownNotActive(iin);

        String otpCode = generateSixDigitOtp();
        PasswordResetDraft draft = new PasswordResetDraft(iin, otpCode, 0, 0);

        saveDraft(iin, draft);
        setCooldown(iin);

        String firstName = (user.getFirstName() != null && !user.getFirstName().isBlank())
                ? user.getFirstName()
                : user.getFullName();
        emailNotificationProducer.sendPasswordResetOtpEmail(user.getEmail(), firstName, otpCode);

        String maskedEmail = maskEmail(user.getEmail());
        return new PasswordResetOtpSentResponse(
                maskedEmail,
                "Код отправлен на почту " + maskedEmail,
                (int) COOLDOWN_TTL.toSeconds(),
                MAX_RESEND_COUNT
        );
    }

    @Transactional(readOnly = true)
    public PasswordResetOtpSentResponse resendOtp(PasswordResetResendRequest request) {
        if (request == null) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Запрос повторной отправки кода не может быть пустым");
        }

        String iin = validateIinDigits(request.iin());
        checkNotBanned(iin);

        PasswordResetDraft draft = getDraftOrThrow(iin);
        User user = getActiveUserByIinOrThrow(iin);
        checkCooldownNotActive(iin);

        if (draft.resendCount() >= MAX_RESEND_COUNT) {
            clearDraftAndCooldown(iin);
            banIin(iin);
            throw new ApiException(
                    ErrorCode.PASSWORD_RESET_BLOCKED,
                    "Превышен лимит попыток восстановления. Попробуйте через 1 час"
            );
        }

        int newResendCount = draft.resendCount() + 1;
        String newOtpCode = generateSixDigitOtp();

        PasswordResetDraft updated = new PasswordResetDraft(iin, newOtpCode, 0, newResendCount);
        saveDraft(iin, updated);
        setCooldown(iin);

        String firstName = (user.getFirstName() != null && !user.getFirstName().isBlank())
                ? user.getFirstName()
                : user.getFullName();
        emailNotificationProducer.sendPasswordResetOtpEmail(user.getEmail(), firstName, newOtpCode);

        String maskedEmail = maskEmail(user.getEmail());
        return new PasswordResetOtpSentResponse(
                maskedEmail,
                "Новый код отправлен на почту " + maskedEmail,
                (int) COOLDOWN_TTL.toSeconds(),
                MAX_RESEND_COUNT - newResendCount
        );
    }

    public PasswordResetCompletedResponse verifyOtp(PasswordResetVerifyOtpRequest request) {
        if (request == null) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Запрос проверки кода не может быть пустым");
        }

        String iin = validateIinDigits(request.iin());
        checkNotBanned(iin);

        String code = request.code() != null ? request.code().trim() : "";
        if (!OTP_PATTERN.matcher(code).matches()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Код подтверждения должен состоять ровно из 6 цифр");
        }

        PasswordResetDraft draft = verifyCodeAgainstDraft(iin, code);
        saveDraft(iin, draft);

        return new PasswordResetCompletedResponse(
                iin,
                "Код подтверждён. Введите новый пароль"
        );
    }

    @Transactional
    public PasswordResetCompletedResponse confirmPasswordReset(PasswordResetConfirmRequest request) {
        if (request == null) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Запрос подтверждения смены пароля не может быть пустым");
        }

        String iin = validateIinDigits(request.iin());
        checkNotBanned(iin);

        String code = request.code() != null ? request.code().trim() : "";
        if (!OTP_PATTERN.matcher(code).matches()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Код подтверждения должен состоять ровно из 6 цифр");
        }

        verifyCodeAgainstDraft(iin, code);

        if (request.confirmPassword() != null && !request.confirmPassword().equals(request.newPassword())) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Пароли не совпадают");
        }

        passwordValidator.validateOrThrow(request.newPassword());

        User user = getActiveUserByIinOrThrow(iin);

        if (passwordEncoder.matches(request.newPassword(), user.getPasswordHash())) {
            throw new ApiException(
                    ErrorCode.SAME_PASSWORD,
                    "Новый пароль должен отличаться от текущего"
            );
        }

        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        user.setFailedLoginAttempts(0);
        user.setLockedUntil(null);
        userRepository.save(user);

        clearDraftAndCooldown(iin);

        String firstName = (user.getFirstName() != null && !user.getFirstName().isBlank())
                ? user.getFirstName()
                : user.getFullName();
        emailNotificationProducer.sendPasswordChangedEmail(user.getEmail(), firstName, user.getIin());

        return new PasswordResetCompletedResponse(
                iin,
                "Пароль от вашей учетной записи TraceDesk был успешно изменен"
        );
    }

    public static String maskEmail(String email) {
        if (email == null || email.isBlank()) {
            return "***";
        }
        String trimmed = email.trim();
        int atIndex = trimmed.indexOf('@');
        if (atIndex <= 0) {
            return "***";
        }
        return trimmed.charAt(0) + "***" + trimmed.substring(atIndex);
    }

    private PasswordResetDraft verifyCodeAgainstDraft(String iin, String code) {
        PasswordResetDraft draft = getDraftOrThrow(iin);
        if (draft.otpCode() == null) {
            throw new ApiException(
                    ErrorCode.OTP_ATTEMPTS_EXCEEDED,
                    "Неверный код 5 раз подряд. Запросите новый код"
            );
        }

        if (!draft.otpCode().equals(code)) {
            int attempts = draft.verifyAttempts() + 1;
            if (attempts < MAX_VERIFY_ATTEMPTS) {
                PasswordResetDraft updated = new PasswordResetDraft(
                        draft.iin(),
                        draft.otpCode(),
                        attempts,
                        draft.resendCount()
                );
                saveDraft(iin, updated);
                throw new ApiException(
                        ErrorCode.INVALID_OTP_CODE,
                        "Неверный код. Осталось попыток: " + (MAX_VERIFY_ATTEMPTS - attempts)
                );
            }

            if (draft.resendCount() >= MAX_RESEND_COUNT) {
                clearDraftAndCooldown(iin);
                banIin(iin);
                throw new ApiException(
                        ErrorCode.PASSWORD_RESET_BLOCKED,
                        "Превышен лимит попыток восстановления. Попробуйте через 1 час"
                );
            }

            PasswordResetDraft invalidated = new PasswordResetDraft(
                    draft.iin(),
                    null,
                    attempts,
                    draft.resendCount()
            );
            saveDraft(iin, invalidated);
            throw new ApiException(
                    ErrorCode.OTP_ATTEMPTS_EXCEEDED,
                    "Неверный код 5 раз подряд. Запросите новый код"
            );
        }

        return draft;
    }

    private String validateIinDigits(String rawIin) {
        String iin = rawIin != null ? rawIin.trim() : null;
        if (iin == null || !TWELVE_DIGITS_PATTERN.matcher(iin).matches()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "ИИН должен состоять ровно из 12 цифр");
        }
        return iin;
    }

    private User getActiveUserByIinOrThrow(String iin) {
        User user = userRepository.findByIin(iin)
                .orElseThrow(() -> new ApiException(
                        ErrorCode.USER_NOT_FOUND,
                        "Пользователь с указанным ИИН не найден"
                ));

        if (user.getStatus() != UserStatus.ACTIVE) {
            ErrorCode code = (user.getStatus() == UserStatus.PENDING_APPROVAL)
                    ? ErrorCode.ACCOUNT_PENDING_APPROVAL
                    : ErrorCode.ACCOUNT_BLOCKED;
            throw new ApiException(
                    code,
                    "Восстановление пароля недоступно для данного статуса учетной записи"
            );
        }

        if (user.getEmail() == null || user.getEmail().isBlank()) {
            throw new ApiException(
                    ErrorCode.VALIDATION_ERROR,
                    "Для данной учетной записи не указана электронная почта"
            );
        }

        return user;
    }

    private void checkNotBanned(String iin) {
        if (Boolean.TRUE.equals(redisTemplate.hasKey(BAN_KEY_PREFIX + iin))) {
            throw new ApiException(
                    ErrorCode.PASSWORD_RESET_BLOCKED,
                    "Превышен лимит попыток восстановления. Попробуйте через 1 час"
            );
        }
    }

    private void checkCooldownNotActive(String iin) {
        String cooldownKey = COOLDOWN_KEY_PREFIX + iin;
        if (Boolean.TRUE.equals(redisTemplate.hasKey(cooldownKey))) {
            Long remainingSeconds = redisTemplate.getExpire(cooldownKey, TimeUnit.SECONDS);
            long waitSec = (remainingSeconds != null && remainingSeconds > 0) ? remainingSeconds : 1;
            throw new ApiException(
                    ErrorCode.OTP_COOLDOWN_ACTIVE,
                    "Повторная отправка кода доступна через " + waitSec + " сек."
            );
        }
    }

    private String generateSixDigitOtp() {
        return String.format("%06d", secureRandom.nextInt(1_000_000));
    }

    private void saveDraft(String iin, PasswordResetDraft draft) {
        try {
            String json = objectMapper.writeValueAsString(draft);
            redisTemplate.opsForValue().set(RESET_KEY_PREFIX + iin, json, RESET_TTL);
        } catch (JsonProcessingException ex) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "Ошибка сериализации состояния сброса пароля");
        }
    }

    private PasswordResetDraft getDraftOrThrow(String iin) {
        String json = redisTemplate.opsForValue().get(RESET_KEY_PREFIX + iin);
        if (json == null || json.isBlank()) {
            throw new ApiException(
                    ErrorCode.PASSWORD_RESET_NOT_FOUND,
                    "Сессия восстановления пароля не найдена или истекла. Запросите код заново"
            );
        }
        try {
            return objectMapper.readValue(json, PasswordResetDraft.class);
        } catch (JsonProcessingException ex) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "Ошибка чтения состояния сброса пароля");
        }
    }

    private void setCooldown(String iin) {
        redisTemplate.opsForValue().set(COOLDOWN_KEY_PREFIX + iin, "1", COOLDOWN_TTL);
    }

    private void banIin(String iin) {
        redisTemplate.opsForValue().set(BAN_KEY_PREFIX + iin, "1", BAN_TTL);
    }

    private void clearDraftAndCooldown(String iin) {
        redisTemplate.delete(List.of(
                RESET_KEY_PREFIX + iin,
                COOLDOWN_KEY_PREFIX + iin
        ));
    }
}
