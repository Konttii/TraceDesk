package com.tracedesk;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tracedesk.domain.entity.User;
import com.tracedesk.domain.enums.Role;
import com.tracedesk.domain.enums.UserStatus;
import com.tracedesk.repository.DepartmentRepository;
import com.tracedesk.repository.PositionRepository;
import com.tracedesk.repository.RegionRepository;
import com.tracedesk.repository.UserRepository;
import com.tracedesk.service.EmailNotificationProducer;
import com.tracedesk.service.PasswordResetService;
import com.tracedesk.web.dto.PasswordResetConfirmRequest;
import com.tracedesk.web.dto.PasswordResetDraft;
import com.tracedesk.web.dto.PasswordResetInitRequest;
import com.tracedesk.web.dto.PasswordResetResendRequest;
import com.tracedesk.web.dto.PasswordResetVerifyOtpRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PasswordResetIntegrationTest {

    private static final String TEST_IIN = "970715300800";
    private static final String TEST_EMAIL = "kuandyk0820@gmail.com";
    private static final String OLD_PASSWORD = "OldStrongPass1!";
    private static final String NEW_PASSWORD = "NewStrongPass2!";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RegionRepository regionRepository;

    @Autowired
    private DepartmentRepository departmentRepository;

    @Autowired
    private PositionRepository positionRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @MockitoSpyBean
    private EmailNotificationProducer emailNotificationProducer;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    @AfterEach
    void cleanup() {
        redisTemplate.delete(List.of(
                PasswordResetService.RESET_KEY_PREFIX + TEST_IIN,
                PasswordResetService.COOLDOWN_KEY_PREFIX + TEST_IIN,
                PasswordResetService.BAN_KEY_PREFIX + TEST_IIN
        ));
        userRepository.findByIin(TEST_IIN).ifPresent(userRepository::delete);
    }

    @Test
    @DisplayName("1. Успешный цикл сброса пароля: init (возвращает maskedEmail) -> confirm -> вход с новым паролем и сброс lockedUntil")
    void successfulPasswordResetUnlocksAccountAndAllowsLoginWithNewPasswordTest() throws Exception {
        User user = createTestUser(UserStatus.ACTIVE);
        user.setFailedLoginAttempts(3);
        user.setLockedUntil(OffsetDateTime.now().plusHours(1));
        userRepository.save(user);

        // До сброса пароля аккаунт заблокирован на 1 час (423 ACCOUNT_LOCKED)
        mockMvc.perform(get("/auth/me").with(httpBasic(TEST_IIN, OLD_PASSWORD)))
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"));

        // Шаг 1: Инициализация восстановления пароля -> возвращает maskedEmail "k***@gmail.com"
        mockMvc.perform(post("/auth/password-reset/init")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new PasswordResetInitRequest(TEST_IIN))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maskedEmail").value("k***@gmail.com"))
                .andExpect(jsonPath("$.cooldownSeconds").value(60))
                .andExpect(jsonPath("$.remainingResends").value(3));

        // Читаем сгенерированный 6-значный код из Redis (pwd:reset:{iin})
        String draftJson = redisTemplate.opsForValue().get(PasswordResetService.RESET_KEY_PREFIX + TEST_IIN);
        assertThat(draftJson).isNotBlank();
        PasswordResetDraft draft = objectMapper.readValue(draftJson, PasswordResetDraft.class);
        assertThat(draft.otpCode()).matches("^\\d{6}$");

        verify(emailNotificationProducer).sendPasswordResetOtpEmail(eq(TEST_EMAIL), eq("Куандык"), eq(draft.otpCode()));

        // Шаг 2: Предварительная проверка OTP-кода (/verify-otp) для перехода к форме ввода нового пароля
        mockMvc.perform(post("/auth/password-reset/verify-otp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new PasswordResetVerifyOtpRequest(TEST_IIN, draft.otpCode())
                        )))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.iin").value(TEST_IIN));

        // Несовпадение нового пароля и его подтверждения -> 400 VALIDATION_ERROR
        mockMvc.perform(post("/auth/password-reset/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new PasswordResetConfirmRequest(TEST_IIN, draft.otpCode(), NEW_PASSWORD, "DifferentPass9!")
                        )))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        // Шаг 3: Подтверждение кода и установка нового пароля с совпадающим confirmPassword
        PasswordResetConfirmRequest confirmRequest = new PasswordResetConfirmRequest(
                TEST_IIN,
                draft.otpCode(),
                NEW_PASSWORD,
                NEW_PASSWORD
        );
        mockMvc.perform(post("/auth/password-reset/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(confirmRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.iin").value(TEST_IIN));

        verify(emailNotificationProducer).sendPasswordChangedEmail(eq(TEST_EMAIL), eq("Куандык"), eq(TEST_IIN));

        // Ключи в Redis очищены, блокировка входа снята
        assertThat(redisTemplate.hasKey(PasswordResetService.RESET_KEY_PREFIX + TEST_IIN)).isFalse();
        assertThat(redisTemplate.hasKey(PasswordResetService.COOLDOWN_KEY_PREFIX + TEST_IIN)).isFalse();

        User updated = userRepository.findByIin(TEST_IIN).orElseThrow();
        assertThat(updated.getFailedLoginAttempts()).isZero();
        assertThat(updated.getLockedUntil()).isNull();

        // Старый пароль больше не подходит, новый пароль успешно авторизует пользователя
        mockMvc.perform(get("/auth/me").with(httpBasic(TEST_IIN, OLD_PASSWORD)))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/auth/me").with(httpBasic(TEST_IIN, NEW_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.iin").value(TEST_IIN));
    }

    @Test
    @DisplayName("2. Ошибка 400 SAME_PASSWORD при попытке задать тот же пароль, что и текущий")
    void confirmRejectsSamePasswordWith400Test() throws Exception {
        createTestUser(UserStatus.ACTIVE);

        mockMvc.perform(post("/auth/password-reset/init")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new PasswordResetInitRequest(TEST_IIN))))
                .andExpect(status().isOk());

        PasswordResetDraft draft = objectMapper.readValue(
                redisTemplate.opsForValue().get(PasswordResetService.RESET_KEY_PREFIX + TEST_IIN),
                PasswordResetDraft.class
        );

        // Попытка указать старый пароль -> 400 SAME_PASSWORD
        PasswordResetConfirmRequest samePasswordRequest = new PasswordResetConfirmRequest(
                TEST_IIN,
                draft.otpCode(),
                OLD_PASSWORD
        );
        mockMvc.perform(post("/auth/password-reset/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(samePasswordRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SAME_PASSWORD"))
                .andExpect(jsonPath("$.message").value("Новый пароль должен отличаться от текущего"));
    }

    @Test
    @DisplayName("3. Ошибка 403 при попытке сбросить пароль для BLOCKED / PENDING_APPROVAL / REJECTED пользователя и 404 для несуществующего ИИН")
    void initRejectsNonActiveUsersWith403AndUnknownIinWith404Test() throws Exception {
        // Несуществующий ИИН -> 404 USER_NOT_FOUND
        mockMvc.perform(post("/auth/password-reset/init")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new PasswordResetInitRequest(TEST_IIN))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));

        User user = createTestUser(UserStatus.PENDING_APPROVAL);

        // PENDING_APPROVAL -> 403 Forbidden
        mockMvc.perform(post("/auth/password-reset/init")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new PasswordResetInitRequest(TEST_IIN))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCOUNT_PENDING_APPROVAL"));

        // BLOCKED -> 403 Forbidden
        user.setStatus(UserStatus.BLOCKED);
        userRepository.save(user);

        mockMvc.perform(post("/auth/password-reset/init")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new PasswordResetInitRequest(TEST_IIN))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCOUNT_BLOCKED"));

        // REJECTED -> 403 Forbidden
        user.setStatus(UserStatus.REJECTED);
        userRepository.save(user);

        mockMvc.perform(post("/auth/password-reset/init")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new PasswordResetInitRequest(TEST_IIN))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCOUNT_BLOCKED"));
    }

    @Test
    @DisplayName("4. Защита от брутфорса OTP: 5 неверных кодов блокируют текущий код (OTP_ATTEMPTS_EXCEEDED), а исчерпание 3 переотправок вешает бан на 1 час")
    void otpBruteForceProtectionAndResendLimitBanTest() throws Exception {
        createTestUser(UserStatus.ACTIVE);

        mockMvc.perform(post("/auth/password-reset/init")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new PasswordResetInitRequest(TEST_IIN))))
                .andExpect(status().isOk());

        // 4 неверные попытки ввода кода -> 400 INVALID_OTP_CODE
        for (int i = 1; i <= 4; i++) {
            mockMvc.perform(post("/auth/password-reset/confirm")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    new PasswordResetConfirmRequest(TEST_IIN, "000000", NEW_PASSWORD)
                            )))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_OTP_CODE"));
        }

        // 5-я неверная попытка -> 400 OTP_ATTEMPTS_EXCEEDED и обнуление otpCode в Redis
        mockMvc.perform(post("/auth/password-reset/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new PasswordResetConfirmRequest(TEST_IIN, "000000", NEW_PASSWORD)
                        )))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("OTP_ATTEMPTS_EXCEEDED"));

        PasswordResetDraft afterFiveErrors = objectMapper.readValue(
                redisTemplate.opsForValue().get(PasswordResetService.RESET_KEY_PREFIX + TEST_IIN),
                PasswordResetDraft.class
        );
        assertThat(afterFiveErrors.otpCode()).isNull();

        // Выполняем 3 разрешённых переотправки кода
        for (int r = 1; r <= 3; r++) {
            redisTemplate.delete(PasswordResetService.COOLDOWN_KEY_PREFIX + TEST_IIN);
            mockMvc.perform(post("/auth/password-reset/resend-otp")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(new PasswordResetResendRequest(TEST_IIN))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.remainingResends").value(3 - r));
        }

        // 4-я попытка переотправки -> удаляет сессию и вешает бан pwd:ban:{iin} на 1 час (429 PASSWORD_RESET_BLOCKED)
        redisTemplate.delete(PasswordResetService.COOLDOWN_KEY_PREFIX + TEST_IIN);
        mockMvc.perform(post("/auth/password-reset/resend-otp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new PasswordResetResendRequest(TEST_IIN))))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("PASSWORD_RESET_BLOCKED"));

        assertThat(redisTemplate.hasKey(PasswordResetService.RESET_KEY_PREFIX + TEST_IIN)).isFalse();
        assertThat(redisTemplate.hasKey(PasswordResetService.BAN_KEY_PREFIX + TEST_IIN)).isTrue();
    }

    private User createTestUser(UserStatus status) {
        User user = new User(
                TEST_IIN,
                "Сериков Куандык",
                passwordEncoder.encode(OLD_PASSWORD),
                Role.INVESTIGATOR
        );
        user.setIin(TEST_IIN);
        user.setEmail(TEST_EMAIL);
        user.setFirstName("Куандык");
        user.setLastName("Сериков");
        user.setStatus(status);
        user.setRegion(regionRepository.findById("01").orElseThrow());
        user.setDepartment(departmentRepository.findById((short) 1).orElseThrow());
        user.setPosition(positionRepository.findById((short) 2).orElseThrow());
        return userRepository.save(user);
    }
}
