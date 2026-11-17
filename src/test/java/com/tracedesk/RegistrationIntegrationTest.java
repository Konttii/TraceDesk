package com.tracedesk;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tracedesk.domain.entity.User;
import com.tracedesk.domain.enums.PasswordStrength;
import com.tracedesk.domain.enums.Role;
import com.tracedesk.domain.enums.UserStatus;
import com.tracedesk.repository.UserRepository;
import com.tracedesk.service.IinValidator;
import com.tracedesk.service.PasswordValidator;
import com.tracedesk.service.RegistrationService;
import com.tracedesk.web.dto.RegisterInitRequest;
import com.tracedesk.web.dto.RegistrationDraft;
import com.tracedesk.web.dto.ResendOtpRequest;
import com.tracedesk.web.dto.VerifyOtpRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RegistrationIntegrationTest {

    private static final String TEST_EMAIL = "new.investigator@tracedesk.kz";
    private static final String TEST_IIN = "980515300111";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private IinValidator iinValidator;

    @Autowired
    private PasswordValidator passwordValidator;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void cleanupTestUserAndRedis() {
        redisTemplate.delete(List.of(
                RegistrationService.DRAFT_KEY_PREFIX + TEST_EMAIL,
                RegistrationService.COOLDOWN_KEY_PREFIX + TEST_EMAIL,
                RegistrationService.BAN_KEY_PREFIX + TEST_EMAIL
        ));
        userRepository.findByIin(TEST_IIN).ifPresent(userRepository::delete);
    }

    @Test
    @DisplayName("Валидаторы ИИН и пароля корректно проверяют формат, дату ГГММДД и оценку надёжности (WEAK/MEDIUM/STRONG)")
    void iinAndPasswordValidatorsTest() {
        assertThat(iinValidator.isValid("980515300111")).isTrue();
        assertThat(iinValidator.isValid("981315300111")).isFalse(); // месяц 13
        assertThat(iinValidator.isValid("980230300111")).isFalse(); // 30 февраля
        assertThat(iinValidator.isValid("123456")).isFalse();

        assertThat(passwordValidator.isValid("short1A")).isFalse();
        assertThat(passwordValidator.evaluateStrength("short1A")).isEqualTo(PasswordStrength.WEAK);

        assertThat(passwordValidator.isValid("ValidPass1")).isTrue();
        assertThat(passwordValidator.evaluateStrength("ValidPass1")).isEqualTo(PasswordStrength.MEDIUM);

        assertThat(passwordValidator.isValid("StrongPass12!")).isTrue();
        assertThat(passwordValidator.evaluateStrength("StrongPass12!")).isEqualTo(PasswordStrength.STRONG);
    }

    @Test
    @DisplayName("Дубликат ИИН или Email в БД возвращает 409 Conflict (IIN_ALREADY_EXISTS / EMAIL_ALREADY_EXISTS)")
    void duplicateIinAndEmailConflictTest() throws Exception {
        RegisterInitRequest duplicateIinReq = new RegisterInitRequest(
                "Арман",
                "Сериков",
                "950101400001", // ИИН Ирины
                TEST_EMAIL,
                "StrongPass12!",
                Role.INVESTIGATOR,
                "01",
                (short) 1,
                (short) 3
        );

        mockMvc.perform(post("/auth/register/init")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(duplicateIinReq)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IIN_ALREADY_EXISTS"));

        RegisterInitRequest duplicateEmailReq = new RegisterInitRequest(
                "Арман",
                "Сериков",
                TEST_IIN,
                "IRINA@tracedesk.kz", // Email Ирины в верхнем регистре
                "StrongPass12!",
                Role.INVESTIGATOR,
                "01",
                (short) 1,
                (short) 3
        );

        mockMvc.perform(post("/auth/register/init")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(duplicateEmailReq)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_ALREADY_EXISTS"));
    }

    @Test
    @DisplayName("Полный цикл регистрации через Redis OTP и RabbitMQ: init -> проверка кулдауна -> verify-otp -> создание PENDING_APPROVAL")
    void fullRegistrationFlowWithRedisOtpTest() throws Exception {
        RegisterInitRequest initRequest = new RegisterInitRequest(
                "Арман",
                "Сериков",
                TEST_IIN,
                TEST_EMAIL,
                "StrongPass12!",
                Role.INVESTIGATOR,
                "01",
                (short) 1,
                (short) 3
        );

        // 1. Инициализация регистрации
        mockMvc.perform(post("/auth/register/init")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(initRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(TEST_EMAIL))
                .andExpect(jsonPath("$.passwordStrength").value("STRONG"));

        // До подтверждения OTP пользователя в PostgreSQL быть НЕ должно
        assertThat(userRepository.existsByIin(TEST_IIN)).isFalse();

        // 2. Попытка сразу вызвать resend-otp блокируется кулдауном 60 сек -> 429
        mockMvc.perform(post("/auth/register/resend-otp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ResendOtpRequest(TEST_EMAIL))))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("OTP_COOLDOWN_ACTIVE"));

        // 3. Неверный OTP-код увеличивает счётчик попыток и возвращает 400 INVALID_OTP_CODE
        mockMvc.perform(post("/auth/register/verify-otp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new VerifyOtpRequest(TEST_EMAIL, "000000"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_OTP_CODE"));

        // 4. Читаем реальный код из Redis и подтверждаем почту
        String draftJson = redisTemplate.opsForValue().get(RegistrationService.DRAFT_KEY_PREFIX + TEST_EMAIL);
        assertThat(draftJson).isNotBlank();
        RegistrationDraft draft = objectMapper.readValue(draftJson, RegistrationDraft.class);
        assertThat(draft.verifyAttempts()).isEqualTo(1);

        mockMvc.perform(post("/auth/register/verify-otp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new VerifyOtpRequest(TEST_EMAIL, draft.otpCode()))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.iin").value(TEST_IIN))
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"));

        // 5. Пользователь создан в PostgreSQL со статусом PENDING_APPROVAL, username == iin, ключи в Redis удалены
        User created = userRepository.findByIin(TEST_IIN).orElseThrow();
        assertThat(created.getUsername()).isEqualTo(TEST_IIN);
        assertThat(created.getFullName()).isEqualTo("Сериков Арман");
        assertThat(created.getStatus()).isEqualTo(UserStatus.PENDING_APPROVAL);
        assertThat(created.getRegion().getId()).isEqualTo("01");
        assertThat(redisTemplate.hasKey(RegistrationService.DRAFT_KEY_PREFIX + TEST_EMAIL)).isFalse();
        assertThat(redisTemplate.hasKey(RegistrationService.COOLDOWN_KEY_PREFIX + TEST_EMAIL)).isFalse();
    }

    @Test
    @DisplayName("2-уровневая защита OTP: 5 неверных вводов обнуляют код, а исчерпание 3 переотправок банит почту на 1 час")
    void twoLevelOtpProtectionAndBanTest() throws Exception {
        RegisterInitRequest initRequest = new RegisterInitRequest(
                "Арман",
                "Сериков",
                TEST_IIN,
                TEST_EMAIL,
                "StrongPass12!",
                Role.INVESTIGATOR,
                "01",
                (short) 1,
                (short) 3
        );

        mockMvc.perform(post("/auth/register/init")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(initRequest)))
                .andExpect(status().isOk());

        // 4 неверные попытки -> 400 INVALID_OTP_CODE
        for (int i = 1; i <= 4; i++) {
            mockMvc.perform(post("/auth/register/verify-otp")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(new VerifyOtpRequest(TEST_EMAIL, "999999"))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_OTP_CODE"));
        }

        // 5-я неверная попытка при resendCount < 3 -> 400 OTP_ATTEMPTS_EXCEEDED и otpCode = null
        mockMvc.perform(post("/auth/register/verify-otp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new VerifyOtpRequest(TEST_EMAIL, "999999"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("OTP_ATTEMPTS_EXCEEDED"));

        RegistrationDraft afterFiveAttempts = objectMapper.readValue(
                redisTemplate.opsForValue().get(RegistrationService.DRAFT_KEY_PREFIX + TEST_EMAIL),
                RegistrationDraft.class
        );
        assertThat(afterFiveAttempts.otpCode()).isNull();

        // Выполняем 3 разрешённых переотправки (снимая кулдаун между ними)
        for (int r = 1; r <= 3; r++) {
            redisTemplate.delete(RegistrationService.COOLDOWN_KEY_PREFIX + TEST_EMAIL);
            mockMvc.perform(post("/auth/register/resend-otp")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(new ResendOtpRequest(TEST_EMAIL))))
                    .andExpect(status().isOk());
        }

        // 4-я попытка переотправки (когда resendCount == 3) -> удаляет черновик, ставит бан на 1 час (429)
        redisTemplate.delete(RegistrationService.COOLDOWN_KEY_PREFIX + TEST_EMAIL);
        mockMvc.perform(post("/auth/register/resend-otp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ResendOtpRequest(TEST_EMAIL))))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("EMAIL_VERIFICATION_BLOCKED"));

        assertThat(redisTemplate.hasKey(RegistrationService.DRAFT_KEY_PREFIX + TEST_EMAIL)).isFalse();
        assertThat(redisTemplate.hasKey(RegistrationService.BAN_KEY_PREFIX + TEST_EMAIL)).isTrue();
    }
}
