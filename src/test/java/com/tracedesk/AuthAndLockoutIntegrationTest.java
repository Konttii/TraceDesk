package com.tracedesk;

import com.tracedesk.domain.entity.User;
import com.tracedesk.domain.enums.Role;
import com.tracedesk.domain.enums.UserStatus;
import com.tracedesk.repository.DepartmentRepository;
import com.tracedesk.repository.PositionRepository;
import com.tracedesk.repository.RegionRepository;
import com.tracedesk.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthAndLockoutIntegrationTest {

    private static final String TEST_USER_IIN = "990808300999";
    private static final String TEST_USER_EMAIL = "stage4.test@tracedesk.kz";
    private static final String TEST_USER_PASSWORD = "StrongPass12!";

    @Autowired
    private MockMvc mockMvc;

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

    @BeforeEach
    @AfterEach
    void cleanupTestUser() {
        userRepository.findByIin(TEST_USER_IIN).ifPresent(userRepository::delete);
    }

    @Test
    @DisplayName("GET /auth/me: вход по ИИН (12 цифр) и по username (обратная совместимость) возвращает полный профиль пользователя")
    void loginByIinAndUsernameReturnsFullProfileTest() throws Exception {
        User irina = userRepository.findByIin("950101400001").orElseThrow();

        // 1. Вход по ИИН Ирины (950101400001)
        mockMvc.perform(get("/auth/me")
                        .with(httpBasic("950101400001", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(irina.getId()))
                .andExpect(jsonPath("$.iin").value("950101400001"))
                .andExpect(jsonPath("$.email").value("irina@tracedesk.kz"))
                .andExpect(jsonPath("$.fullName").value(irina.getFullName()))
                .andExpect(jsonPath("$.firstName").value("Ирина"))
                .andExpect(jsonPath("$.lastName").value("Соколова"))
                .andExpect(jsonPath("$.role").value("INVESTIGATOR"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.region.id").value("01"))
                .andExpect(jsonPath("$.region.name").value("г. Астана"))
                .andExpect(jsonPath("$.department.id").value(1))
                .andExpect(jsonPath("$.department.name").value("1 - Следственное управление"))
                .andExpect(jsonPath("$.position.id").value(2))
                .andExpect(jsonPath("$.position.name").value("Старший следователь"));

        // 2. Обратная совместимость: вход по старому username ("irina")
        mockMvc.perform(get("/auth/me")
                        .with(httpBasic("irina", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.iin").value("950101400001"))
                .andExpect(jsonPath("$.role").value("INVESTIGATOR"));

        // 3. Вход администратора по ИИН (000000000001), у которого region/department/position == null
        mockMvc.perform(get("/auth/me")
                        .with(httpBasic("000000000001", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.iin").value("000000000001"))
                .andExpect(jsonPath("$.role").value("ADMIN"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.region").isEmpty())
                .andExpect(jsonPath("$.department").isEmpty())
                .andExpect(jsonPath("$.position").isEmpty());
    }

    @Test
    @DisplayName("Проверка статусов аккаунта: PENDING_APPROVAL -> 403 ACCOUNT_PENDING_APPROVAL, REJECTED и BLOCKED -> 403 ACCOUNT_BLOCKED")
    void accountStatusVerificationTest() throws Exception {
        User user = createTestUser(UserStatus.PENDING_APPROVAL);

        // 1. Статус PENDING_APPROVAL -> 403 ACCOUNT_PENDING_APPROVAL
        mockMvc.perform(get("/auth/me")
                        .with(httpBasic(TEST_USER_IIN, TEST_USER_PASSWORD)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCOUNT_PENDING_APPROVAL"))
                .andExpect(jsonPath("$.message").value("Ваша заявка еще находится на рассмотрении у администратора"));

        // 2. Статус REJECTED -> 403 ACCOUNT_BLOCKED
        user.setStatus(UserStatus.REJECTED);
        userRepository.save(user);

        mockMvc.perform(get("/auth/me")
                        .with(httpBasic(TEST_USER_IIN, TEST_USER_PASSWORD)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCOUNT_BLOCKED"))
                .andExpect(jsonPath("$.message").value("Доступ к системе заблокирован администратором"));

        // 3. Статус BLOCKED -> 403 ACCOUNT_BLOCKED
        user.setStatus(UserStatus.BLOCKED);
        userRepository.save(user);

        mockMvc.perform(get("/auth/me")
                        .with(httpBasic(TEST_USER_IIN, TEST_USER_PASSWORD)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCOUNT_BLOCKED"))
                .andExpect(jsonPath("$.message").value("Доступ к системе заблокирован администратором"));
    }

    @Test
    @DisplayName("Прогрессивная защита от Brute-Force: 3 ошибки -> бан 5 мин, затем 2 попытки -> бан 1 час (и далее каждые 2 ошибки -> 1 час), успех сбрасывает счётчики")
    void bruteForceLockoutAndResetTest() throws Exception {
        createTestUser(UserStatus.ACTIVE);

        // 1. Две неудачные попытки ввода пароля -> счётчик растёт до 2, но успешный вход сбрасывает его в 0
        mockMvc.perform(get("/auth/me").with(httpBasic(TEST_USER_IIN, "WrongPass1!")))
                .andExpect(status().isUnauthorized());
        assertThat(userRepository.findByIin(TEST_USER_IIN).orElseThrow().getFailedLoginAttempts()).isEqualTo(1);

        mockMvc.perform(get("/auth/me").with(httpBasic(TEST_USER_IIN, "WrongPass2!")))
                .andExpect(status().isUnauthorized());
        assertThat(userRepository.findByIin(TEST_USER_IIN).orElseThrow().getFailedLoginAttempts()).isEqualTo(2);

        // Успешный вход до 3-й ошибки сбрасывает failedLoginAttempts в 0
        mockMvc.perform(get("/auth/me").with(httpBasic(TEST_USER_IIN, TEST_USER_PASSWORD)))
                .andExpect(status().isOk());
        assertThat(userRepository.findByIin(TEST_USER_IIN).orElseThrow().getFailedLoginAttempts()).isZero();

        // 2. Три неудачные попытки подряд -> lockedUntil = now + 5m, failedLoginAttempts = 3
        for (int i = 1; i <= 3; i++) {
            mockMvc.perform(get("/auth/me").with(httpBasic(TEST_USER_IIN, "WrongPass" + i)))
                    .andExpect(status().isUnauthorized());
        }

        User shortLockedUser = userRepository.findByIin(TEST_USER_IIN).orElseThrow();
        assertThat(shortLockedUser.getFailedLoginAttempts()).isEqualTo(3);
        assertThat(shortLockedUser.getLockedUntil()).isNotNull();
        assertThat(shortLockedUser.getLockedUntil()).isAfter(OffsetDateTime.now().plusMinutes(4));
        assertThat(shortLockedUser.getLockedUntil()).isBeforeOrEqualTo(OffsetDateTime.now().plusMinutes(5).plusSeconds(5));

        // 3. Попытка входа даже с ПРАВИЛЬНЫМ паролем во время 5-минутной блокировки возвращает 423 ACCOUNT_LOCKED
        mockMvc.perform(get("/auth/me").with(httpBasic(TEST_USER_IIN, TEST_USER_PASSWORD)))
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"))
                .andExpect(jsonPath("$.message").value("Аккаунт временно заблокирован на 5 минут из-за 3 неверных попыток ввода пароля"));

        // 4. Имитируем истечение 5 минут -> даются 2 попытки; 1-я ошибка ещё не блокирует
        shortLockedUser.setLockedUntil(OffsetDateTime.now().minusSeconds(1));
        userRepository.save(shortLockedUser);

        mockMvc.perform(get("/auth/me").with(httpBasic(TEST_USER_IIN, "WrongPass4")))
                .andExpect(status().isUnauthorized());
        User afterFourthFail = userRepository.findByIin(TEST_USER_IIN).orElseThrow();
        assertThat(afterFourthFail.getFailedLoginAttempts()).isEqualTo(4);
        assertThat(afterFourthFail.getLockedUntil()).isNull();

        // 5. 2-я ошибка после 5-минутного бана (всего 5-я) -> блокировка на 1 час
        mockMvc.perform(get("/auth/me").with(httpBasic(TEST_USER_IIN, "WrongPass5")))
                .andExpect(status().isUnauthorized());
        User hourLockedUser = userRepository.findByIin(TEST_USER_IIN).orElseThrow();
        assertThat(hourLockedUser.getFailedLoginAttempts()).isEqualTo(5);
        assertThat(hourLockedUser.getLockedUntil()).isNotNull();
        assertThat(hourLockedUser.getLockedUntil()).isAfter(OffsetDateTime.now().plusMinutes(55));

        mockMvc.perform(get("/auth/me").with(httpBasic(TEST_USER_IIN, TEST_USER_PASSWORD)))
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"))
                .andExpect(jsonPath("$.message").value("Аккаунт временно заблокирован на 1 час из-за превышения количества неверных попыток ввода пароля"));

        // 6. Имитируем истечение 1 часа -> снова 2 попытки -> 2 ошибки снова блокируют на 1 час
        hourLockedUser.setLockedUntil(OffsetDateTime.now().minusSeconds(1));
        userRepository.save(hourLockedUser);

        mockMvc.perform(get("/auth/me").with(httpBasic(TEST_USER_IIN, "WrongPass6")))
                .andExpect(status().isUnauthorized());
        assertThat(userRepository.findByIin(TEST_USER_IIN).orElseThrow().getLockedUntil()).isNull();

        mockMvc.perform(get("/auth/me").with(httpBasic(TEST_USER_IIN, "WrongPass7")))
                .andExpect(status().isUnauthorized());
        User secondHourLockedUser = userRepository.findByIin(TEST_USER_IIN).orElseThrow();
        assertThat(secondHourLockedUser.getFailedLoginAttempts()).isEqualTo(7);
        assertThat(secondHourLockedUser.getLockedUntil()).isAfter(OffsetDateTime.now().plusMinutes(55));

        // 7. После истечения блокировки успешный вход разрешён и сбрасывает счётчики
        secondHourLockedUser.setLockedUntil(OffsetDateTime.now().minusMinutes(1));
        userRepository.save(secondHourLockedUser);

        mockMvc.perform(get("/auth/me").with(httpBasic(TEST_USER_IIN, TEST_USER_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.iin").value(TEST_USER_IIN));

        User unlockedUser = userRepository.findByIin(TEST_USER_IIN).orElseThrow();
        assertThat(unlockedUser.getLockedUntil()).isNull();
        assertThat(unlockedUser.getFailedLoginAttempts()).isZero();
    }

    private User createTestUser(UserStatus status) {
        User user = new User(
                TEST_USER_IIN,
                "Тестов Данияр",
                passwordEncoder.encode(TEST_USER_PASSWORD),
                Role.INVESTIGATOR
        );
        user.setIin(TEST_USER_IIN);
        user.setEmail(TEST_USER_EMAIL);
        user.setFirstName("Данияр");
        user.setLastName("Тестов");
        user.setStatus(status);
        user.setRegion(regionRepository.findById("01").orElseThrow());
        user.setDepartment(departmentRepository.findById((short) 1).orElseThrow());
        user.setPosition(positionRepository.findById((short) 3).orElseThrow());
        return userRepository.save(user);
    }
}
