package com.tracedesk;

import com.tracedesk.domain.entity.Ticket;
import com.tracedesk.domain.enums.TicketStatus;
import com.tracedesk.repository.TicketRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class TicketIntegrationTest {

    // Валидный минимальный PNG-файл (error.png) с правильной сигнатурой 89 50 4E 47
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
    private TicketRepository ticketRepository;

    @BeforeEach
    void cleanTickets() {
        ticketRepository.deleteAll();
    }

    @Test
    @DisplayName("Проверка 1: Полный основной сценарий от создания обращения с error.png до закрытия и скачивания вложения")
    void fullMainScenarioTest() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "error.png",
                "image/png",
                SYNTHETIC_PNG
        );

        // 1. Ирина создаёт обращение «Не скачивается PDF» со скриншотом error.png
        MvcResult createResult = mockMvc.perform(multipart("/tickets")
                        .file(file)
                        .param("title", "Не скачивается PDF")
                        .param("description", "При нажатии кнопки выгрузки возникает ошибка")
                        .with(httpBasic("irina", "password")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.ticketNumber").isNotEmpty())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andReturn();

        Long ticketId = extractId(createResult.getResponse().getContentAsString());

        // 2. Олег находит обращение с фильтром OPEN
        mockMvc.perform(get("/tickets")
                        .param("status", "OPEN")
                        .with(httpBasic("oleg", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(ticketId))
                .andExpect(jsonPath("$.content[0].title").value("Не скачивается PDF"));

        // 3. Олег берёт обращение в работу
        mockMvc.perform(post("/tickets/{id}/assign", ticketId)
                        .with(httpBasic("oleg", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.assigneeUsername").value("oleg"));

        // 4. Олег отвечает «Проверьте доступ к материалам дела»
        mockMvc.perform(post("/tickets/{id}/replies", ticketId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\": \"Проверьте доступ к материалам дела\"}")
                        .with(httpBasic("oleg", "password")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value("Проверьте доступ к материалам дела"));

        // 5. Олег закрывает обращение
        mockMvc.perform(post("/tickets/{id}/close", ticketId)
                        .with(httpBasic("oleg", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));

        // 6. Ирина открывает карточку и видит CLOSED, ответ и 3 записи истории (OPEN -> IN_PROGRESS -> CLOSED)
        mockMvc.perform(get("/tickets/{id}", ticketId)
                        .with(httpBasic("irina", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.replies.length()").value(1))
                .andExpect(jsonPath("$.replies[0].message").value("Проверьте доступ к материалам дела"))
                .andExpect(jsonPath("$.history.length()").value(3))
                .andExpect(jsonPath("$.history[0].newStatus").value("OPEN"))
                .andExpect(jsonPath("$.history[1].oldStatus").value("OPEN"))
                .andExpect(jsonPath("$.history[1].newStatus").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.history[2].oldStatus").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.history[2].newStatus").value("CLOSED"));

        // 7. Скачанный скриншот побайтово совпадает с отправленным
        byte[] downloaded = mockMvc.perform(get("/tickets/{id}/attachment", ticketId)
                        .with(httpBasic("irina", "password")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"))
                .andReturn()
                .getResponse()
                .getContentAsByteArray();

        assertThat(downloaded).isEqualTo(SYNTHETIC_PNG);
    }

    @Test
    @DisplayName("Проверка 2: Запрет доступа к чужому обращению (403) и запрет закрытия без ответа (409)")
    void permissionsAndInvalidStatesTest() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "error.png", "image/png", SYNTHETIC_PNG);

        MvcResult createResult = mockMvc.perform(multipart("/tickets")
                        .file(file)
                        .param("title", "Тикет Ирины")
                        .param("description", "Описание проблемы Ирины")
                        .with(httpBasic("irina", "password")))
                .andExpect(status().isCreated())
                .andReturn();

        Long ticketId = extractId(createResult.getResponse().getContentAsString());

        // 1. Павел НЕ может прочитать карточку Ирины (403)
        mockMvc.perform(get("/tickets/{id}", ticketId)
                        .with(httpBasic("pavel", "password")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        // 2. Павел НЕ может скачать скриншот Ирины (403)
        mockMvc.perform(get("/tickets/{id}/attachment", ticketId)
                        .with(httpBasic("pavel", "password")))
                .andExpect(status().isForbidden());

        // 3. Ирина НЕ может вызвать команду оператора /assign (403)
        mockMvc.perform(post("/tickets/{id}/assign", ticketId)
                        .with(httpBasic("irina", "password")))
                .andExpect(status().isForbidden());

        // 4. Олег берёт обращение в работу и пробует закрыть его БЕЗ ответа -> 409 Conflict
        mockMvc.perform(post("/tickets/{id}/assign", ticketId)
                        .with(httpBasic("oleg", "password")))
                .andExpect(status().isOk());

        mockMvc.perform(post("/tickets/{id}/close", ticketId)
                        .with(httpBasic("oleg", "password")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CLOSE_WITHOUT_REPLY"));

        // 5. После ответа и закрытия попытка ответить повторно возвращает 409 Conflict
        mockMvc.perform(post("/tickets/{id}/replies", ticketId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\": \"Ответ оператора\"}")
                        .with(httpBasic("oleg", "password")))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/tickets/{id}/close", ticketId)
                        .with(httpBasic("oleg", "password")))
                .andExpect(status().isOk());

        mockMvc.perform(post("/tickets/{id}/replies", ticketId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\": \"Ответ после закрытия\"}")
                        .with(httpBasic("oleg", "password")))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("Проверка конкурентности: Два оператора одновременно берут одно OPEN-обращение, один получает 200, второй — 409")
    void concurrentAssignRaceConditionTest() throws Exception {
        MvcResult createResult = mockMvc.perform(multipart("/tickets")
                        .param("title", "Гонка операторов")
                        .param("description", "Проверка оптимистической блокировки")
                        .with(httpBasic("irina", "password")))
                .andExpect(status().isCreated())
                .andReturn();

        Long ticketId = extractId(createResult.getResponse().getContentAsString());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch readyLatch = new CountDownLatch(2);
        CountDownLatch startLatch = new CountDownLatch(1);

        Callable<Integer> olegTask = () -> {
            readyLatch.countDown();
            startLatch.await();
            return mockMvc.perform(post("/tickets/{id}/assign", ticketId)
                            .with(httpBasic("oleg", "password")))
                    .andReturn().getResponse().getStatus();
        };

        Callable<Integer> annaTask = () -> {
            readyLatch.countDown();
            startLatch.await();
            return mockMvc.perform(post("/tickets/{id}/assign", ticketId)
                            .with(httpBasic("anna", "password")))
                    .andReturn().getResponse().getStatus();
        };

        List<Future<Integer>> futures = new ArrayList<>();
        futures.add(executor.submit(olegTask));
        futures.add(executor.submit(annaTask));

        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown(); // Одновременный старт двух потоков!

        int status1 = futures.get(0).get(10, TimeUnit.SECONDS);
        int status2 = futures.get(1).get(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(List.of(status1, status2)).containsExactlyInAnyOrder(200, 409);

        Ticket updated = ticketRepository.findById(ticketId).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(TicketStatus.IN_PROGRESS);
    }

    private Long extractId(String json) {
        Matcher matcher = Pattern.compile("\"id\"\\s*:\\s*(\\d+)").matcher(json);
        if (matcher.find()) {
            return Long.parseLong(matcher.group(1));
        }
        throw new IllegalStateException("ID не найден в ответе: " + json);
    }
}
