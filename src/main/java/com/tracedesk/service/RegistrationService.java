package com.tracedesk.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tracedesk.domain.entity.Department;
import com.tracedesk.domain.entity.Position;
import com.tracedesk.domain.entity.Region;
import com.tracedesk.domain.entity.User;
import com.tracedesk.domain.enums.PasswordStrength;
import com.tracedesk.domain.enums.Role;
import com.tracedesk.domain.enums.UserStatus;
import com.tracedesk.repository.DepartmentRepository;
import com.tracedesk.repository.PositionRepository;
import com.tracedesk.repository.RegionRepository;
import com.tracedesk.repository.UserRepository;
import com.tracedesk.web.dto.RegisterInitRequest;
import com.tracedesk.web.dto.RegistrationCompletedResponse;
import com.tracedesk.web.dto.RegistrationDraft;
import com.tracedesk.web.dto.RegistrationOtpSentResponse;
import com.tracedesk.web.dto.ResendOtpRequest;
import com.tracedesk.web.dto.VerifyOtpRequest;
import com.tracedesk.web.exception.ApiException;
import com.tracedesk.web.exception.ErrorCode;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

@Service
public class RegistrationService {

    public static final String DRAFT_KEY_PREFIX = "reg:draft:";
    public static final String COOLDOWN_KEY_PREFIX = "reg:cooldown:";
    public static final String BAN_KEY_PREFIX = "reg:ban:";

    public static final Duration DRAFT_TTL = Duration.ofMinutes(10);
    public static final Duration COOLDOWN_TTL = Duration.ofSeconds(60);
    public static final Duration BAN_TTL = Duration.ofHours(1);

    public static final int MAX_VERIFY_ATTEMPTS = 5;
    public static final int MAX_RESEND_COUNT = 3;

    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "^[A-Za-z0-9_%+-]+(?:\\.[A-Za-z0-9_%+-]+)*@(?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?\\.)+[A-Za-z]{2,24}$"
    );
    private static final Pattern NAME_PATTERN = Pattern.compile(
            "^[A-Za-zА-Яа-яЁёӘәҒғҚқҢңӨөҰұҮүҺһІі]+(?:[\\s-][A-Za-zА-Яа-яЁёӘәҒғҚқҢңӨөҰұҮүҺһІі]+)*$"
    );
    private static final Pattern TWELVE_DIGITS_PATTERN = Pattern.compile("^\\d{12}$");
    private static final Pattern OTP_PATTERN = Pattern.compile("^\\d{6}$");

    private final StringRedisTemplate redisTemplate;
    private final UserRepository userRepository;
    private final RegionRepository regionRepository;
    private final DepartmentRepository departmentRepository;
    private final PositionRepository positionRepository;
    private final PasswordEncoder passwordEncoder;
    private final IinValidator iinValidator;
    private final PasswordValidator passwordValidator;
    private final EmailNotificationProducer emailNotificationProducer;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SecureRandom secureRandom = new SecureRandom();

    public RegistrationService(
            StringRedisTemplate redisTemplate,
            UserRepository userRepository,
            RegionRepository regionRepository,
            DepartmentRepository departmentRepository,
            PositionRepository positionRepository,
            PasswordEncoder passwordEncoder,
            IinValidator iinValidator,
            PasswordValidator passwordValidator,
            EmailNotificationProducer emailNotificationProducer
    ) {
        this.redisTemplate = redisTemplate;
        this.userRepository = userRepository;
        this.regionRepository = regionRepository;
        this.departmentRepository = departmentRepository;
        this.positionRepository = positionRepository;
        this.passwordEncoder = passwordEncoder;
        this.iinValidator = iinValidator;
        this.passwordValidator = passwordValidator;
        this.emailNotificationProducer = emailNotificationProducer;
    }

    @Transactional(readOnly = true)
    public RegistrationOtpSentResponse initRegistration(RegisterInitRequest request) {
        if (request == null) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Данные регистрации не могут быть пустыми");
        }

        String normalizedEmail = validateAndNormalizeEmail(request.email());
        checkNotBanned(normalizedEmail);

        String firstName = validatePersonName(request.firstName(), "Имя");
        String lastName = validatePersonName(request.lastName(), "Фамилия");
        String iin = request.iin() != null ? request.iin().trim() : null;

        if (iin == null || !TWELVE_DIGITS_PATTERN.matcher(iin).matches()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "ИИН должен состоять ровно из 12 цифр");
        }

        passwordValidator.validateOrThrow(request.password());
        PasswordStrength passwordStrength = passwordValidator.evaluateStrength(request.password());

        if (request.role() == null || (request.role() != Role.INVESTIGATOR && request.role() != Role.SUPPORT)) {
            throw new ApiException(
                    ErrorCode.VALIDATION_ERROR,
                    "Для самостоятельной регистрации доступны только роли INVESTIGATOR или SUPPORT"
            );
        }

        checkUserUniqueness(iin, normalizedEmail);
        iinValidator.validateOrThrow(iin);

        String regionId = request.regionId() != null ? request.regionId().trim() : null;
        if (regionId == null || regionId.isBlank() || !regionRepository.existsById(regionId)) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Указанный регион не найден в справочнике");
        }
        if (request.departmentId() == null || !departmentRepository.existsById(request.departmentId())) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Указанное следственное управление не найдено в справочнике");
        }
        if (request.positionId() == null || !positionRepository.existsById(request.positionId())) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Указанная должность не найдена в справочнике");
        }

        String otpCode = generateSixDigitOtp();
        RegistrationDraft draft = new RegistrationDraft(
                firstName,
                lastName,
                iin,
                normalizedEmail,
                passwordEncoder.encode(request.password()),
                request.role(),
                regionId,
                request.departmentId(),
                request.positionId(),
                otpCode,
                0,
                0
        );

        saveDraft(normalizedEmail, draft);
        setCooldown(normalizedEmail);
        emailNotificationProducer.sendOtpVerificationEmail(normalizedEmail, firstName, otpCode);

        return new RegistrationOtpSentResponse(
                normalizedEmail,
                "Код подтверждения отправлен на " + normalizedEmail,
                (int) COOLDOWN_TTL.toSeconds(),
                MAX_RESEND_COUNT,
                passwordStrength
        );
    }

    @Transactional
    public RegistrationCompletedResponse verifyOtp(VerifyOtpRequest request) {
        if (request == null) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Запрос подтверждения кода не может быть пустым");
        }

        String normalizedEmail = validateAndNormalizeEmail(request.email());
        checkNotBanned(normalizedEmail);

        String code = request.code() != null ? request.code().trim() : "";
        if (!OTP_PATTERN.matcher(code).matches()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Код подтверждения должен состоять ровно из 6 цифр");
        }

        RegistrationDraft draft = getDraftOrThrow(normalizedEmail);
        if (draft.otpCode() == null) {
            throw new ApiException(
                    ErrorCode.OTP_ATTEMPTS_EXCEEDED,
                    "Неверный код 5 раз подряд. Запросите новый код"
            );
        }

        if (!draft.otpCode().equals(code)) {
            int attempts = draft.verifyAttempts() + 1;
            if (attempts < MAX_VERIFY_ATTEMPTS) {
                RegistrationDraft updated = new RegistrationDraft(
                        draft.firstName(),
                        draft.lastName(),
                        draft.iin(),
                        draft.email(),
                        draft.passwordHash(),
                        draft.role(),
                        draft.regionId(),
                        draft.departmentId(),
                        draft.positionId(),
                        draft.otpCode(),
                        attempts,
                        draft.resendCount()
                );
                saveDraft(normalizedEmail, updated);
                throw new ApiException(
                        ErrorCode.INVALID_OTP_CODE,
                        "Неверный код. Осталось попыток: " + (MAX_VERIFY_ATTEMPTS - attempts)
                );
            }

            if (draft.resendCount() >= MAX_RESEND_COUNT) {
                clearDraftAndCooldown(normalizedEmail);
                banEmail(normalizedEmail);
                throw new ApiException(
                        ErrorCode.EMAIL_VERIFICATION_BLOCKED,
                        "Лимит попыток исчерпан. Укажите другую почту или попробуйте через 1 час"
                );
            }

            RegistrationDraft invalidated = new RegistrationDraft(
                    draft.firstName(),
                    draft.lastName(),
                    draft.iin(),
                    draft.email(),
                    draft.passwordHash(),
                    draft.role(),
                    draft.regionId(),
                    draft.departmentId(),
                    draft.positionId(),
                    null,
                    attempts,
                    draft.resendCount()
            );
            saveDraft(normalizedEmail, invalidated);
            throw new ApiException(
                    ErrorCode.OTP_ATTEMPTS_EXCEEDED,
                    "Неверный код 5 раз подряд. Запросите новый код"
            );
        }

        // Код верный — повторно проверяем уникальность ИИН и Email в PostgreSQL
        checkUserUniqueness(draft.iin(), draft.email());

        Region region = regionRepository.findById(draft.regionId())
                .orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_ERROR, "Указанный регион не найден в справочнике"));
        Department department = departmentRepository.findById(draft.departmentId())
                .orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_ERROR, "Указанное следственное управление не найдено в справочнике"));
        Position position = positionRepository.findById(draft.positionId())
                .orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_ERROR, "Указанная должность не найдена в справочнике"));

        User user = new User();
        user.setUsername(draft.iin());
        user.setIin(draft.iin());
        user.setEmail(draft.email());
        user.setFirstName(draft.firstName());
        user.setLastName(draft.lastName());
        user.setFullName(draft.lastName() + " " + draft.firstName());
        user.setPasswordHash(draft.passwordHash());
        user.setRole(draft.role());
        user.setStatus(UserStatus.PENDING_APPROVAL);
        user.setRegion(region);
        user.setDepartment(department);
        user.setPosition(position);

        User saved = userRepository.save(user);
        clearDraftAndCooldown(normalizedEmail);

        return new RegistrationCompletedResponse(
                saved.getId(),
                saved.getIin(),
                saved.getEmail(),
                saved.getStatus(),
                "Почта подтверждена. Заявка отправлена на проверку администратору"
        );
    }

    public RegistrationOtpSentResponse resendOtp(ResendOtpRequest request) {
        if (request == null) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Запрос повторной отправки кода не может быть пустым");
        }

        String normalizedEmail = validateAndNormalizeEmail(request.email());
        checkNotBanned(normalizedEmail);

        RegistrationDraft draft = getDraftOrThrow(normalizedEmail);

        String cooldownKey = COOLDOWN_KEY_PREFIX + normalizedEmail;
        if (Boolean.TRUE.equals(redisTemplate.hasKey(cooldownKey))) {
            Long remainingSeconds = redisTemplate.getExpire(cooldownKey, TimeUnit.SECONDS);
            long waitSec = (remainingSeconds != null && remainingSeconds > 0) ? remainingSeconds : 1;
            throw new ApiException(
                    ErrorCode.OTP_COOLDOWN_ACTIVE,
                    "Повторная отправка кода доступна через " + waitSec + " сек."
            );
        }

        if (draft.resendCount() >= MAX_RESEND_COUNT) {
            clearDraftAndCooldown(normalizedEmail);
            banEmail(normalizedEmail);
            throw new ApiException(
                    ErrorCode.EMAIL_VERIFICATION_BLOCKED,
                    "Лимит отправки кодов исчерпан. Укажите другую почту"
            );
        }

        int newResendCount = draft.resendCount() + 1;
        String newOtpCode = generateSixDigitOtp();

        RegistrationDraft updated = new RegistrationDraft(
                draft.firstName(),
                draft.lastName(),
                draft.iin(),
                draft.email(),
                draft.passwordHash(),
                draft.role(),
                draft.regionId(),
                draft.departmentId(),
                draft.positionId(),
                newOtpCode,
                0,
                newResendCount
        );

        saveDraft(normalizedEmail, updated);
        setCooldown(normalizedEmail);
        emailNotificationProducer.sendOtpVerificationEmail(normalizedEmail, draft.firstName(), newOtpCode);

        return new RegistrationOtpSentResponse(
                normalizedEmail,
                "Новый код подтверждения отправлен на " + normalizedEmail,
                (int) COOLDOWN_TTL.toSeconds(),
                MAX_RESEND_COUNT - newResendCount,
                null
        );
    }

    private void checkUserUniqueness(String iin, String email) {
        if (userRepository.existsByIin(iin)) {
            throw new ApiException(ErrorCode.IIN_ALREADY_EXISTS, "Пользователь с таким ИИН уже зарегистрирован");
        }
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new ApiException(ErrorCode.EMAIL_ALREADY_EXISTS, "Пользователь с таким Email уже зарегистрирован");
        }
    }

    private void checkNotBanned(String normalizedEmail) {
        if (Boolean.TRUE.equals(redisTemplate.hasKey(BAN_KEY_PREFIX + normalizedEmail))) {
            throw new ApiException(
                    ErrorCode.EMAIL_VERIFICATION_BLOCKED,
                    "Лимит попыток исчерпан. Укажите другую почту или попробуйте через 1 час"
            );
        }
    }

    private String validateAndNormalizeEmail(String rawEmail) {
        if (rawEmail == null || rawEmail.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Email не может быть пустым");
        }
        String normalized = rawEmail.trim().toLowerCase(Locale.ROOT);
        int atIndex = normalized.indexOf('@');
        if (normalized.length() > 150
                || atIndex <= 0
                || atIndex > 64
                || !EMAIL_PATTERN.matcher(normalized).matches()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Некорректный формат электронной почты");
        }
        return normalized;
    }

    private String validatePersonName(String value, String fieldLabel) {
        if (value == null || value.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, fieldLabel + " не может быть пустым");
        }
        String trimmed = value.trim();
        if (trimmed.length() < 2 || trimmed.length() > 50) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, fieldLabel + " должно содержать от 2 до 50 символов");
        }
        if (!NAME_PATTERN.matcher(trimmed).matches()) {
            throw new ApiException(
                    ErrorCode.VALIDATION_ERROR,
                    fieldLabel + " должно содержать только буквы (без цифр и спецсимволов)"
            );
        }
        return trimmed;
    }

    private String generateSixDigitOtp() {
        return String.format("%06d", secureRandom.nextInt(1_000_000));
    }

    private void saveDraft(String normalizedEmail, RegistrationDraft draft) {
        try {
            String json = objectMapper.writeValueAsString(draft);
            redisTemplate.opsForValue().set(DRAFT_KEY_PREFIX + normalizedEmail, json, DRAFT_TTL);
        } catch (JsonProcessingException ex) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "Ошибка сериализации черновика регистрации");
        }
    }

    private RegistrationDraft getDraftOrThrow(String normalizedEmail) {
        String json = redisTemplate.opsForValue().get(DRAFT_KEY_PREFIX + normalizedEmail);
        if (json == null || json.isBlank()) {
            throw new ApiException(
                    ErrorCode.REGISTRATION_DRAFT_NOT_FOUND,
                    "Черновик регистрации не найден или время ожидания истекло. Заполните анкету заново"
            );
        }
        try {
            return objectMapper.readValue(json, RegistrationDraft.class);
        } catch (JsonProcessingException ex) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "Ошибка чтения черновика регистрации");
        }
    }

    private void setCooldown(String normalizedEmail) {
        redisTemplate.opsForValue().set(COOLDOWN_KEY_PREFIX + normalizedEmail, "1", COOLDOWN_TTL);
    }

    private void banEmail(String normalizedEmail) {
        redisTemplate.opsForValue().set(BAN_KEY_PREFIX + normalizedEmail, "1", BAN_TTL);
    }

    private void clearDraftAndCooldown(String normalizedEmail) {
        redisTemplate.delete(List.of(
                DRAFT_KEY_PREFIX + normalizedEmail,
                COOLDOWN_KEY_PREFIX + normalizedEmail
        ));
    }
}
