package com.tracedesk;

import com.tracedesk.domain.entity.Region;
import com.tracedesk.domain.entity.Ticket;
import com.tracedesk.domain.entity.User;
import com.tracedesk.domain.enums.Role;
import com.tracedesk.domain.enums.TicketStatus;
import com.tracedesk.domain.enums.UserStatus;
import com.tracedesk.repository.RegionRepository;
import com.tracedesk.repository.TicketRepository;
import com.tracedesk.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DictionaryAndDomainIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RegionRepository regionRepository;

    @Autowired
    private TicketRepository ticketRepository;

    @Test
    @DisplayName("Публичные справочники доступны без авторизации и возвращают данные из V3")
    void dictionariesArePublicAndPopulated() throws Exception {
        mockMvc.perform(get("/api/dictionaries/regions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(20))
                .andExpect(jsonPath("$[0].id").value("01"))
                .andExpect(jsonPath("$[0].name").value("г. Астана"))
                .andExpect(jsonPath("$[3].id").value("04"))
                .andExpect(jsonPath("$[3].name").value("Актюбинская область"));

        mockMvc.perform(get("/api/dictionaries/departments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].name").value("1 - Следственное управление"));

        mockMvc.perform(get("/api/dictionaries/positions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(8))
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].name").value("Следователь по особо важным делам"));
    }

    @Test
    @DisplayName("DemoDataInitializer создаёт администратора и обновляет демо-пользователей с регионами")
    void demoUsersAndAdminInitializedWithProfiles() {
        User admin = userRepository.findByIin("000000000001").orElseThrow();
        assertThat(admin.getUsername()).isEqualTo("admin");
        assertThat(admin.getEmail()).isEqualTo("admin@tracedesk.kz");
        assertThat(admin.getFirstName()).isEqualTo("Администратор");
        assertThat(admin.getLastName()).isEqualTo("Системы");
        assertThat(admin.getRole()).isEqualTo(Role.ADMIN);
        assertThat(admin.getStatus()).isEqualTo(UserStatus.ACTIVE);

        User irina = userRepository.findByEmailIgnoreCase("IRINA@TRACEDESK.KZ").orElseThrow();
        assertThat(irina.getUsername()).isEqualTo("irina");
        assertThat(irina.getIin()).isEqualTo("950101400001");
        assertThat(irina.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(irina.getRegion()).isNotNull();
        assertThat(irina.getRegion().getId()).isEqualTo("01");
        assertThat(irina.getRegion().getName()).isEqualTo("г. Астана");
        assertThat(irina.getDepartment()).isNotNull();
        assertThat(irina.getPosition()).isNotNull();

        User pavel = userRepository.findByIin("940202300002").orElseThrow();
        assertThat(pavel.getUsername()).isEqualTo("pavel");
        assertThat(pavel.getRegion().getId()).isEqualTo("04");

        User oleg = userRepository.findByIin("930303300003").orElseThrow();
        assertThat(oleg.getUsername()).isEqualTo("oleg");
        assertThat(oleg.getRegion().getId()).isEqualTo("01");

        User anna = userRepository.findByIin("960404400004").orElseThrow();
        assertThat(anna.getUsername()).isEqualTo("anna");
        assertThat(anna.getRegion().getId()).isEqualTo("04");

        assertThat(userRepository.existsByIin("000000000001")).isTrue();
        assertThat(userRepository.existsByEmailIgnoreCase("ADMIN@TRACEDESK.KZ")).isTrue();
        assertThat(userRepository.findWithProfileById(irina.getId())).isPresent();
    }

    @Test
    @DisplayName("TicketRepository загружает region через EntityGraph без LazyInitializationException")
    void ticketRepositoryLoadsRegionEagerlyViaEntityGraph() {
        User irina = userRepository.findByUsername("irina").orElseThrow();
        Region astana = regionRepository.findById("01").orElseThrow();

        Long seq = ticketRepository.getNextTicketSequenceValue();
        Ticket ticket = new Ticket();
        ticket.setTicketNumber(String.format("TD-%05d", seq));
        ticket.setTitle("Тест региона");
        ticket.setDescription("Проверка загрузки региона тикета");
        ticket.setStatus(TicketStatus.OPEN);
        ticket.setAuthor(irina);
        ticket.setRegion(astana);
        Ticket saved = ticketRepository.save(ticket);

        try {
            Ticket loaded = ticketRepository.findWithDetailsById(saved.getId()).orElseThrow();
            assertThat(loaded.getRegion()).isNotNull();
            assertThat(loaded.getRegion().getId()).isEqualTo("01");
            assertThat(loaded.getRegion().getName()).isEqualTo("г. Астана");

            var page = ticketRepository.findByAuthorId(irina.getId(), PageRequest.of(0, 100));
            assertThat(page.getContent())
                    .filteredOn(t -> t.getId().equals(saved.getId()))
                    .singleElement()
                    .satisfies(t -> {
                        assertThat(t.getRegion()).isNotNull();
                        assertThat(t.getRegion().getId()).isEqualTo("01");
                    });
        } finally {
            ticketRepository.deleteById(saved.getId());
        }
    }
}
