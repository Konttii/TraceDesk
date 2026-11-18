package com.tracedesk;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tracedesk.domain.entity.User;
import com.tracedesk.domain.enums.Role;
import com.tracedesk.domain.enums.UserStatus;
import com.tracedesk.repository.TicketRepository;
import com.tracedesk.repository.UserRepository;
import com.tracedesk.service.RegistrationService;
import com.tracedesk.web.dto.RegisterInitRequest;
import com.tracedesk.web.dto.RegistrationDraft;
import com.tracedesk.web.dto.VerifyOtpRequest;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.test.web.servlet.MvcResult;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class Stage6EndToEndIntegrationTest {

    private static final String IIN_CANDIDATE_1 = "970715300801";
    private static final String EMAIL_CANDIDATE_1 = "stage6.candidate1@tracedesk.kz";
    private static final String IIN_CANDIDATE_2 = "980920400802";
    private static final String EMAIL_CANDIDATE_2 = "stage6.candidate2@tracedesk.kz";
    private static final String IIN_OTHER = "961110300803";
    private static final String EMAIL_OTHER = "stage6.other@tracedesk.kz";
    private static final String STRONG_PASSWORD = "StrongPass12!";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TicketRepository ticketRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    @AfterEach
    void cleanup() {
        ticketRepository.deleteAll();
        for (String email : List.of(EMAIL_CANDIDATE_1, EMAIL_CANDIDATE_2, EMAIL_OTHER)) {
            redisTemplate.delete(List.of(
                    RegistrationService.DRAFT_KEY_PREFIX + email,
                    RegistrationService.COOLDOWN_KEY_PREFIX + email,
                    RegistrationService.BAN_KEY_PREFIX + email
            ));
        }
        for (String iin : List.of(IIN_CANDIDATE_1, IIN_CANDIDATE_2, IIN_OTHER)) {
            userRepository.findByIin(iin).ifPresent(userRepository::delete);
        }
    }

    @Test
    @DisplayName("SPA index.html доступен без авторизации и содержит формы входа по ИИН, регистрации, OTP и админ-панель")
    void spaIndexPageIsPubliclyAccessibleWithoutAuthTest() throws Exception {
        mockMvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"loginIin\"")))
                .andExpect(content().string(containsString("id=\"registerForm\"")))
                .andExpect(content().string(containsString("id=\"passwordStrengthFill\"")))
                .andExpect(content().string(containsString("id=\"otpModal\"")))
                .andExpect(content().string(containsString("id=\"adminWorkspace\"")))
                .andExpect(content().string(containsString("/api/dictionaries/regions")))
                .andExpect(content().string(containsString("/auth/register/verify-otp")));
    }

    @Test
    @DisplayName("Запрет регистрации с дублирующимся ИИН или Email (409 Conflict) после подтверждения OTP")
    void duplicateRegistrationIinAndEmailReturn409ConflictTest() throws Exception {
        registerAndVerifyOtp(IIN_CANDIDATE_1, EMAIL_CANDIDATE_1, "Тимур", "Ахметов", Role.INVESTIGATOR, "04");

        // Повторная регистрация с тем же ИИН -> 409 IIN_ALREADY_EXISTS
        RegisterInitRequest dupIinReq = new RegisterInitRequest(
                "Другой",
                "Пользователь",
                IIN_CANDIDATE_1,
                EMAIL_OTHER,
                STRONG_PASSWORD,
                Role.INVESTIGATOR,
                "01",
                (short) 1,
                (short) 2
        );
        mockMvc.perform(post("/auth/register/init")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dupIinReq)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IIN_ALREADY_EXISTS"));

        // Повторная регистрация с тем же Email (в другом регистре) -> 409 EMAIL_ALREADY_EXISTS
        RegisterInitRequest dupEmailReq = new RegisterInitRequest(
                "Другой",
                "Пользователь",
                IIN_OTHER,
                EMAIL_CANDIDATE_1.toUpperCase(),
                STRONG_PASSWORD,
                Role.SUPPORT,
                "01",
                (short) 2,
                (short) 3
        );
        mockMvc.perform(post("/auth/register/init")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dupEmailReq)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_ALREADY_EXISTS"));
    }

    @Test
    @DisplayName("Сквозной сценарий: регистрация -> PENDING_APPROVAL -> одобрение/отклонение админом -> вход по ИИН и блокировка на 1 час после 3 ошибок")
    void fullRegistrationAdminDecisionAndLockoutFlowTest() throws Exception {
        Long approvedUserId = registerAndVerifyOtp(
                IIN_CANDIDATE_1, EMAIL_CANDIDATE_1, "Тимур", "Ахметов", Role.INVESTIGATOR, "04"
        );
        Long rejectedUserId = registerAndVerifyOtp(
                IIN_CANDIDATE_2, EMAIL_CANDIDATE_2, "Мадина", "Серикова", Role.SUPPORT, "01"
        );

        // 1. Пока заявка в статусе PENDING_APPROVAL — вход по ИИН возвращает 403 ACCOUNT_PENDING_APPROVAL
        mockMvc.perform(get("/auth/me")
                        .with(httpBasic(IIN_CANDIDATE_1, STRONG_PASSWORD)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCOUNT_PENDING_APPROVAL"));

        // 2. Администратор одобряет первую заявку (PENDING_APPROVAL -> ACTIVE) и отклоняет вторую (PENDING_APPROVAL -> REJECTED)
        mockMvc.perform(post("/admin/registrations/{id}/approve", approvedUserId)
                        .with(httpBasic("000000000001", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        mockMvc.perform(post("/admin/registrations/{id}/reject", rejectedUserId)
                        .with(httpBasic("000000000001", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));

        // Повторное решение по уже обработанной заявке -> 409 INVALID_USER_STATUS
        mockMvc.perform(post("/admin/registrations/{id}/approve", approvedUserId)
                        .with(httpBasic("000000000001", "password")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_USER_STATUS"));

        // 3. Отклонённый пользователь не может войти (403 ACCOUNT_BLOCKED)
        mockMvc.perform(get("/auth/me")
                        .with(httpBasic(IIN_CANDIDATE_2, STRONG_PASSWORD)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCOUNT_BLOCKED"));

        // 4. Одобренный пользователь успешно входит по ИИН
        mockMvc.perform(get("/auth/me")
                        .with(httpBasic(IIN_CANDIDATE_1, STRONG_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.iin").value(IIN_CANDIDATE_1))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.region.id").value("04"));

        // 5. 3 неверных пароля блокируют вход на 1 час -> 423 ACCOUNT_LOCKED
        for (int attempt = 1; attempt <= 3; attempt++) {
            mockMvc.perform(get("/auth/me")
                            .with(httpBasic(IIN_CANDIDATE_1, "WrongPassword" + attempt)))
                    .andExpect(status().isUnauthorized());
        }

        User lockedUser = userRepository.findByIin(IIN_CANDIDATE_1).orElseThrow();
        assertThat(lockedUser.getLockedUntil()).isNotNull();
        assertThat(lockedUser.getLockedUntil()).isAfter(OffsetDateTime.now().plusMinutes(55));

        mockMvc.perform(get("/auth/me")
                        .with(httpBasic(IIN_CANDIDATE_1, STRONG_PASSWORD)))
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"));

        // 6. Администратор разблокирует пользователя -> вход снова работает
        mockMvc.perform(post("/admin/users/{id}/unblock", approvedUserId)
                        .with(httpBasic("000000000001", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.lockedUntil").isEmpty());

        mockMvc.perform(get("/auth/me")
                        .with(httpBasic(IIN_CANDIDATE_1, STRONG_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.iin").value(IIN_CANDIDATE_1));
    }

    @Test
    @DisplayName("Изоляция регионов для нового следователя '04': SUPPORT '01' не видит и не может взять тикет, а SUPPORT '04' доводит его до CLOSED")
    void newlyApprovedInvestigatorRegion04IsolatedFromSupportRegion01Test() throws Exception {
        Long investigatorId = registerAndVerifyOtp(
                IIN_CANDIDATE_1, EMAIL_CANDIDATE_1, "Тимур", "Ахметов", Role.INVESTIGATOR, "04"
        );

        mockMvc.perform(post("/admin/registrations/{id}/approve", investigatorId)
                        .with(httpBasic("000000000001", "password")))
                .andExpect(status().isOk());

        // Новый следователь из региона '04' создаёт тикет по ИИН
        MvcResult createResult = mockMvc.perform(multipart("/tickets")
                        .param("title", "Не работает ЭЦП в регионе 04")
                        .param("description", "Ошибка подписания протокола в Актюбинской области")
                        .with(httpBasic(IIN_CANDIDATE_1, STRONG_PASSWORD)))
                .andExpect(status().isCreated())
                .andReturn();
        Long ticketId = extractId(createResult.getResponse().getContentAsString());

        // Оператор SUPPORT из региона '01' (Олег, 930303300003) не видит тикет и не может взять его в работу
        mockMvc.perform(get("/tickets")
                        .with(httpBasic("930303300003", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));

        mockMvc.perform(get("/tickets/{id}", ticketId)
                        .with(httpBasic("930303300003", "password")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        mockMvc.perform(post("/tickets/{id}/assign", ticketId)
                        .with(httpBasic("930303300003", "password")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        // Оператор SUPPORT из региона '04' (Анна, 960404400004) видит тикет, берет в работу, отвечает и закрывает
        mockMvc.perform(get("/tickets")
                        .with(httpBasic("960404400004", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].regionId").value("04"));

        mockMvc.perform(post("/tickets/{id}/assign", ticketId)
                        .with(httpBasic("960404400004", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"));

        mockMvc.perform(post("/tickets/{id}/replies", ticketId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\": \"Обновили корневой сертификат ЭЦП\"}")
                        .with(httpBasic("960404400004", "password")))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/tickets/{id}/close", ticketId)
                        .with(httpBasic("960404400004", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));
    }

    private Long registerAndVerifyOtp(
            String iin,
            String email,
            String firstName,
            String lastName,
            Role role,
            String regionId
    ) throws Exception {
        RegisterInitRequest initRequest = new RegisterInitRequest(
                firstName,
                lastName,
                iin,
                email,
                STRONG_PASSWORD,
                role,
                regionId,
                (short) 1,
                (short) 2
        );

        mockMvc.perform(post("/auth/register/init")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(initRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email));

        String draftJson = redisTemplate.opsForValue().get(RegistrationService.DRAFT_KEY_PREFIX + email);
        assertThat(draftJson).isNotBlank();
        RegistrationDraft draft = objectMapper.readValue(draftJson, RegistrationDraft.class);

        MvcResult verifyResult = mockMvc.perform(post("/auth/register/verify-otp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new VerifyOtpRequest(email, draft.otpCode()))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value(UserStatus.PENDING_APPROVAL.name()))
                .andReturn();

        return extractLongField(verifyResult.getResponse().getContentAsString(), "userId");
    }

    private Long extractId(String json) {
        return extractLongField(json, "id");
    }

    private Long extractLongField(String json, String fieldName) {
        Matcher matcher = Pattern.compile("\"" + fieldName + "\"\\s*:\\s*(\\d+)").matcher(json);
        if (matcher.find()) {
            return Long.parseLong(matcher.group(1));
        }
        throw new IllegalStateException(fieldName + " не найден в ответе: " + json);
    }
}
