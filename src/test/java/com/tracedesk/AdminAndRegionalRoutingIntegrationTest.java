package com.tracedesk;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tracedesk.domain.entity.User;
import com.tracedesk.domain.enums.Role;
import com.tracedesk.domain.enums.UserStatus;
import com.tracedesk.repository.DepartmentRepository;
import com.tracedesk.repository.PositionRepository;
import com.tracedesk.repository.RegionRepository;
import com.tracedesk.repository.TicketRepository;
import com.tracedesk.repository.UserRepository;
import com.tracedesk.service.EmailNotificationProducer;
import com.tracedesk.web.dto.UpdateUserProfileRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.OffsetDateTime;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminAndRegionalRoutingIntegrationTest {

    private static final String PENDING_IIN_1 = "990101300555";
    private static final String PENDING_EMAIL_1 = "pending1@tracedesk.kz";
    private static final String PENDING_IIN_2 = "990202400666";
    private static final String PENDING_EMAIL_2 = "pending2@tracedesk.kz";
    private static final String SECOND_ADMIN_IIN = "990303300777";
    private static final String SECOND_ADMIN_EMAIL = "admin2@tracedesk.kz";

    private static final byte[] SYNTHETIC_PNG = new byte[]{
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
            0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
            0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, (byte) 0xC4,
            (byte) 0x89, 0x00, 0x00, 0x00, 0x0A, 0x49, 0x44, 0x41,
            0x54, 0x78, (byte) 0x9C, 0x63, 0x00, 0x01, 0x00, 0x00,
            0x05, 0x00, 0x01, 0x0D, 0x0A, 0x2D, (byte) 0xB4, 0x00,
            0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44, (byte) 0xAE,
            0x42, 0x60, (byte) 0x82
    };

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TicketRepository ticketRepository;

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
        ticketRepository.deleteAll();
        userRepository.findByIin(PENDING_IIN_1).ifPresent(userRepository::delete);
        userRepository.findByIin(PENDING_IIN_2).ifPresent(userRepository::delete);
        userRepository.findByIin(SECOND_ADMIN_IIN).ifPresent(userRepository::delete);
    }

    @Test
    @DisplayName("Защита /admin/** ролью ADMIN: INVESTIGATOR и SUPPORT получают 403 ACCESS_DENIED")
    void adminEndpointsRequireAdminRoleTest() throws Exception {
        mockMvc.perform(get("/admin/registrations")
                        .with(httpBasic("irina", "password")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        mockMvc.perform(get("/admin/users")
                        .with(httpBasic("oleg", "password")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    @DisplayName("Админ-панель: заявки PENDING_APPROVAL, approve/reject с RabbitMQ, редактирование профиля, block/unblock")
    void adminRegistrationWorkflowAndUserManagementTest() throws Exception {
        User pending1 = createUser(PENDING_IIN_1, PENDING_EMAIL_1, "Ерлан", "Касымов", Role.INVESTIGATOR, UserStatus.PENDING_APPROVAL, "02");
        User pending2 = createUser(PENDING_IIN_2, PENDING_EMAIL_2, "Айгерим", "Нурова", Role.SUPPORT, UserStatus.PENDING_APPROVAL, "04");

        // 1. Список заявок PENDING_APPROVAL
        mockMvc.perform(get("/admin/registrations")
                        .with(httpBasic("000000000001", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].iin").value(PENDING_IIN_1))
                .andExpect(jsonPath("$[0].region.id").value("02"))
                .andExpect(jsonPath("$[0].department.id").value(1))
                .andExpect(jsonPath("$[0].position.id").value(3))
                .andExpect(jsonPath("$[0].createdAt").isNotEmpty());

        // 2. Одобрение заявки 1 -> статус ACTIVE + отправка письма через RabbitMQ
        mockMvc.perform(post("/admin/registrations/{id}/approve", pending1.getId())
                        .with(httpBasic("000000000001", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        verify(emailNotificationProducer).sendAccountDecisionEmail(PENDING_EMAIL_1, "Ерлан", PENDING_IIN_1, "APPROVE");

        // Одобренный пользователь может войти по ИИН
        mockMvc.perform(get("/auth/me")
                        .with(httpBasic(PENDING_IIN_1, "StrongPass12!")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.iin").value(PENDING_IIN_1));

        // 3. Отклонение заявки 2 -> статус REJECTED + отправка письма об отказе
        mockMvc.perform(post("/admin/registrations/{id}/reject", pending2.getId())
                        .with(httpBasic("000000000001", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));

        verify(emailNotificationProducer).sendAccountDecisionEmail(PENDING_EMAIL_2, "Айгерим", PENDING_IIN_2, "REJECT");

        // Отклонённый пользователь не может войти
        mockMvc.perform(get("/auth/me")
                        .with(httpBasic(PENDING_IIN_2, "StrongPass12!")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCOUNT_BLOCKED"));

        // 4. Фильтрация списка сотрудников и карточка пользователя
        mockMvc.perform(get("/admin/users")
                        .param("role", "INVESTIGATOR")
                        .param("regionId", "02")
                        .param("status", "ACTIVE")
                        .with(httpBasic("000000000001", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].iin").value(PENDING_IIN_1));

        mockMvc.perform(get("/admin/users/{id}", pending1.getId())
                        .with(httpBasic("000000000001", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(PENDING_EMAIL_1));

        // 5. Редактирование профиля сотрудника
        UpdateUserProfileRequest updateReq = new UpdateUserProfileRequest(
                "Ерлан-Обновлённый",
                "Касымов",
                Role.SUPPORT,
                "01",
                (short) 2,
                (short) 1
        );
        mockMvc.perform(put("/admin/users/{id}", pending1.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateReq))
                        .with(httpBasic("000000000001", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.firstName").value("Ерлан-Обновлённый"))
                .andExpect(jsonPath("$.role").value("SUPPORT"))
                .andExpect(jsonPath("$.region.id").value("01"))
                .andExpect(jsonPath("$.department.id").value(2))
                .andExpect(jsonPath("$.position.id").value(1));

        // 6. Блокировка и разблокировка (со сбросом lockedUntil и failedLoginAttempts)
        User updatedUser = userRepository.findById(pending1.getId()).orElseThrow();
        updatedUser.setFailedLoginAttempts(2);
        updatedUser.setLockedUntil(OffsetDateTime.now().plusMinutes(30));
        userRepository.save(updatedUser);

        mockMvc.perform(post("/admin/users/{id}/block", pending1.getId())
                        .with(httpBasic("000000000001", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("BLOCKED"));

        mockMvc.perform(post("/admin/users/{id}/unblock", pending1.getId())
                        .with(httpBasic("000000000001", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.failedLoginAttempts").value(0))
                .andExpect(jsonPath("$.lockedUntil").isEmpty());
    }

    @Test
    @DisplayName("Региональная маршрутизация тикетов: изоляция саппортов по региону, доступ ADMIN ко всем регионам")
    void regionalTicketRoutingAndIsolationTest() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "error.png", "image/png", SYNTHETIC_PNG);

        // 1. Ирина (регион 01 — Астана) создаёт тикет -> тикет получает regionId = 01
        MvcResult astanaResult = mockMvc.perform(multipart("/tickets")
                        .file(file)
                        .param("title", "Проблема в Астане")
                        .param("description", "Описание проблемы в регионе 01")
                        .with(httpBasic("950101400001", "password")))
                .andExpect(status().isCreated())
                .andReturn();
        Long astanaTicketId = extractId(astanaResult.getResponse().getContentAsString());

        // 2. Павел (регион 04 — Актюбинская область) создаёт тикет -> тикет получает regionId = 04
        MvcResult aktobeResult = mockMvc.perform(multipart("/tickets")
                        .param("title", "Проблема в Актобе")
                        .param("description", "Описание проблемы в регионе 04")
                        .with(httpBasic("940202300002", "password")))
                .andExpect(status().isCreated())
                .andReturn();
        Long aktobeTicketId = extractId(aktobeResult.getResponse().getContentAsString());

        // 3. Оператор Олег (01) видит только тикет Астаны (01) с regionId и regionName
        mockMvc.perform(get("/tickets")
                        .with(httpBasic("930303300003", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(astanaTicketId))
                .andExpect(jsonPath("$.content[0].regionId").value("01"))
                .andExpect(jsonPath("$.content[0].regionName").value("г. Астана"));

        // 4. Оператор Анна (04) видит только тикет Актобе (04)
        mockMvc.perform(get("/tickets")
                        .with(httpBasic("960404400004", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(aktobeTicketId))
                .andExpect(jsonPath("$.content[0].regionId").value("04"))
                .andExpect(jsonPath("$.content[0].regionName").value("Актюбинская область"));

        // 5. Анна (04) НЕ может читать, брать в работу или скачивать вложение тикета Астаны (01) -> 403 ACCESS_DENIED
        mockMvc.perform(get("/tickets/{id}", astanaTicketId)
                        .with(httpBasic("960404400004", "password")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        mockMvc.perform(post("/tickets/{id}/assign", astanaTicketId)
                        .with(httpBasic("960404400004", "password")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        mockMvc.perform(get("/tickets/{id}/attachment", astanaTicketId)
                        .with(httpBasic("960404400004", "password")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        // 5.1. Олег (01) НЕ может читать и брать в работу тикет Актобе (04) -> 403 ACCESS_DENIED
        mockMvc.perform(get("/tickets/{id}", aktobeTicketId)
                        .with(httpBasic("930303300003", "password")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        mockMvc.perform(post("/tickets/{id}/assign", aktobeTicketId)
                        .with(httpBasic("930303300003", "password")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        // 6. ADMIN видит все тикеты, может фильтровать по ?regionId=01 и читать/скачивать любой тикет
        mockMvc.perform(get("/tickets")
                        .with(httpBasic("000000000001", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));

        mockMvc.perform(get("/tickets")
                        .param("regionId", "01")
                        .with(httpBasic("000000000001", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(astanaTicketId));

        mockMvc.perform(get("/tickets/{id}", astanaTicketId)
                        .with(httpBasic("000000000001", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.regionId").value("01"))
                .andExpect(jsonPath("$.regionName").value("г. Астана"));

        mockMvc.perform(get("/tickets/{id}/attachment", astanaTicketId)
                        .with(httpBasic("000000000001", "password")))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Защита администратора: запрет самоблокировки (SELF_ACTION_FORBIDDEN) и понижения роли единственного активного ADMIN (LAST_ADMIN_PROTECTION)")
    void adminSelfBlockAndLastAdminProtectionTest() throws Exception {
        User admin = userRepository.findByIin("000000000001").orElseThrow();
        assertThat(userRepository.countByRoleAndStatus(Role.ADMIN, UserStatus.ACTIVE)).isEqualTo(1L);

        // 1. Попытка администратора заблокировать самого себя -> 409 SELF_ACTION_FORBIDDEN
        mockMvc.perform(post("/admin/users/{id}/block", admin.getId())
                        .with(httpBasic("000000000001", "password")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SELF_ACTION_FORBIDDEN"));

        User adminAfterBlockAttempt = userRepository.findById(admin.getId()).orElseThrow();
        assertThat(adminAfterBlockAttempt.getStatus()).isEqualTo(UserStatus.ACTIVE);

        // 2. Попытка администратора отклонить собственную учётную запись -> 409 SELF_ACTION_FORBIDDEN
        mockMvc.perform(post("/admin/registrations/{id}/reject", admin.getId())
                        .with(httpBasic("000000000001", "password")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SELF_ACTION_FORBIDDEN"));

        // 3. Попытка понизить роль единственного активного ADMIN до SUPPORT -> 409 LAST_ADMIN_PROTECTION
        UpdateUserProfileRequest demoteSoleAdminReq = new UpdateUserProfileRequest(
                null,
                null,
                Role.SUPPORT,
                null,
                null,
                null
        );
        mockMvc.perform(put("/admin/users/{id}", admin.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(demoteSoleAdminReq))
                        .with(httpBasic("000000000001", "password")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LAST_ADMIN_PROTECTION"));

        User adminAfterDemoteAttempt = userRepository.findById(admin.getId()).orElseThrow();
        assertThat(adminAfterDemoteAttempt.getRole()).isEqualTo(Role.ADMIN);

        // 4. При наличии второго активного ADMIN попытка изменить собственную роль возвращает 409 SELF_ACTION_FORBIDDEN,
        //    а понижение роли второго ADMIN успешно проходит
        User secondAdmin = createUser(
                SECOND_ADMIN_IIN,
                SECOND_ADMIN_EMAIL,
                "Дамир",
                "Алимов",
                Role.ADMIN,
                UserStatus.ACTIVE,
                "01"
        );
        assertThat(userRepository.countByRoleAndStatus(Role.ADMIN, UserStatus.ACTIVE)).isEqualTo(2L);

        mockMvc.perform(put("/admin/users/{id}", admin.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(demoteSoleAdminReq))
                        .with(httpBasic("000000000001", "password")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SELF_ACTION_FORBIDDEN"));

        mockMvc.perform(put("/admin/users/{id}", secondAdmin.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(demoteSoleAdminReq))
                        .with(httpBasic("000000000001", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("SUPPORT"));

        assertThat(userRepository.countByRoleAndStatus(Role.ADMIN, UserStatus.ACTIVE)).isEqualTo(1L);
    }

    private User createUser(
            String iin,
            String email,
            String firstName,
            String lastName,
            Role role,
            UserStatus status,
            String regionId
    ) {
        User user = new User(iin, lastName + " " + firstName, passwordEncoder.encode("StrongPass12!"), role);
        user.setIin(iin);
        user.setEmail(email);
        user.setFirstName(firstName);
        user.setLastName(lastName);
        user.setStatus(status);
        user.setRegion(regionRepository.findById(regionId).orElseThrow());
        user.setDepartment(departmentRepository.findById((short) 1).orElseThrow());
        user.setPosition(positionRepository.findById((short) 3).orElseThrow());
        return userRepository.save(user);
    }

    private Long extractId(String json) {
        Matcher matcher = Pattern.compile("\"id\"\\s*:\\s*(\\d+)").matcher(json);
        if (matcher.find()) {
            return Long.parseLong(matcher.group(1));
        }
        throw new IllegalStateException("ID не найден в ответе: " + json);
    }
}
