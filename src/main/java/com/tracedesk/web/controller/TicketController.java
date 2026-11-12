package com.tracedesk.web.controller;

import com.tracedesk.config.AppUserPrincipal;
import com.tracedesk.domain.enums.TicketStatus;
import com.tracedesk.service.TicketService;
import com.tracedesk.web.dto.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/tickets")
@Tag(name = "Tickets", description = "Управление обращениями в техподдержку (OPEN → IN_PROGRESS → CLOSED)")
public class TicketController {

    private final TicketService ticketService;

    public TicketController(TicketService ticketService) {
        this.ticketService = ticketService;
    }

    @Operation(
            summary = "3.1 Создать обращение",
            description = "Принимает тему, описание и необязательный скриншот (PNG/JPEG до 5 МБ) через multipart/form-data. Сохраняет статус OPEN и возвращает 201 с ID и номером обращения."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Обращение успешно создано"),
            @ApiResponse(responseCode = "400", description = "Пустые тема/описание, неверный формат файла или превышен размер 5 МБ"),
            @ApiResponse(responseCode = "401", description = "Не авторизован")
    })
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<TicketCreatedResponse> createTicket(
            @AuthenticationPrincipal AppUserPrincipal principal,
            @Parameter(description = "Тема обращения (обязательно)") @RequestParam(value = "title", required = false) String title,
            @Parameter(description = "Описание проблемы (обязательно)") @RequestParam(value = "description", required = false) String description,
            @Parameter(description = "Скриншот PNG или JPEG до 5 МБ (необязательно)") @RequestPart(value = "file", required = false) MultipartFile file,
            @Parameter(hidden = true) @RequestPart(value = "attachment", required = false) MultipartFile attachment
    ) {
        MultipartFile resolvedFile = (file != null && !file.isEmpty()) ? file : attachment;
        TicketCreatedResponse created = ticketService.createTicket(
                principal.getUser(),
                title,
                description,
                resolvedFile
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @Operation(
            summary = "3.2 / 3.4 Список доступных обращений с фильтрацией и пагинацией",
            description = "Оператор SUPPORT видит все обращения. Следователь INVESTIGATOR видит только свои обращения."
    )
    @GetMapping
    public ResponseEntity<Page<TicketSummaryResponse>> listTickets(
            @AuthenticationPrincipal AppUserPrincipal principal,
            @Parameter(description = "Фильтр по статусу (OPEN, IN_PROGRESS, CLOSED)") @RequestParam(value = "status", required = false) TicketStatus status,
            @Parameter(description = "Номер страницы (начиная с 0)") @RequestParam(value = "page", defaultValue = "0") int page,
            @Parameter(description = "Размер страницы (1..100)") @RequestParam(value = "size", defaultValue = "10") int size
    ) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 100);
        Pageable pageable = PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "createdAt"));
        return ResponseEntity.ok(ticketService.listTickets(principal.getUser(), status, pageable));
    }

    @Operation(
            summary = "3.4 Получить карточку обращения с ответами и историей переходов",
            description = "Возвращает полную карточку обращения. Чужому следователю возвращается 403 Forbidden."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Карточка обращения"),
            @ApiResponse(responseCode = "403", description = "Попытка чтения чужого обращения"),
            @ApiResponse(responseCode = "404", description = "Обращение не найдено")
    })
    @GetMapping("/{id}")
    public ResponseEntity<TicketDetailsResponse> getTicket(
            @AuthenticationPrincipal AppUserPrincipal principal,
            @PathVariable("id") Long id
    ) {
        return ResponseEntity.ok(ticketService.getTicketDetails(principal.getUser(), id));
    }

    @Operation(
            summary = "3.2 Взять обращение в работу (OPEN → IN_PROGRESS)",
            description = "Назначает текущего оператора SUPPORT. При одновременном вызове двумя операторами назначается только один, второй получает 409 Conflict."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Обращение взято в работу"),
            @ApiResponse(responseCode = "403", description = "Доступно только для роли SUPPORT"),
            @ApiResponse(responseCode = "409", description = "Обращение уже взято в работу другим оператором или закрыто")
    })
    @PostMapping("/{id}/assign")
    public ResponseEntity<TicketDetailsResponse> assignTicket(
            @AuthenticationPrincipal AppUserPrincipal principal,
            @PathVariable("id") Long id
    ) {
        return ResponseEntity.ok(ticketService.assignTicket(principal.getUser(), id));
    }

    @Operation(
            summary = "3.3 Добавить ответ оператора",
            description = "Назначенный оператор добавляет непустой ответ в состоянии IN_PROGRESS. Попытка ответить в статусе OPEN или CLOSED возвращает 409 Conflict."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Ответ сохранён"),
            @ApiResponse(responseCode = "400", description = "Пустой текст ответа"),
            @ApiResponse(responseCode = "403", description = "Вызвано не назначенным оператором"),
            @ApiResponse(responseCode = "409", description = "Обращение не в статусе IN_PROGRESS")
    })
    @PostMapping("/{id}/replies")
    public ResponseEntity<ReplyResponse> addReply(
            @AuthenticationPrincipal AppUserPrincipal principal,
            @PathVariable("id") Long id,
            @Valid @RequestBody CreateReplyRequest request
    ) {
        ReplyResponse reply = ticketService.addReply(principal.getUser(), id, request.message());
        return ResponseEntity.status(HttpStatus.CREATED).body(reply);
    }

    @Operation(
            summary = "3.3 Закрыть обращение (IN_PROGRESS → CLOSED)",
            description = "Закрытие возможно только после хотя бы одного ответа назначенного оператора. Попытка закрыть без ответа возвращает 409 Conflict (CLOSE_WITHOUT_REPLY)."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Обращение закрыто"),
            @ApiResponse(responseCode = "403", description = "Вызвано не назначенным оператором"),
            @ApiResponse(responseCode = "409", description = "Попытка закрыть без ответа или обращение не в статусе IN_PROGRESS")
    })
    @PostMapping("/{id}/close")
    public ResponseEntity<TicketDetailsResponse> closeTicket(
            @AuthenticationPrincipal AppUserPrincipal principal,
            @PathVariable("id") Long id
    ) {
        return ResponseEntity.ok(ticketService.closeTicket(principal.getUser(), id));
    }

    @Operation(
            summary = "3.4 Скачать прикреплённый скриншот из MinIO",
            description = "Отдаёт файл автору обращения или оператору SUPPORT. Чужой следователь получает 403 Forbidden."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Файл вложения"),
            @ApiResponse(responseCode = "403", description = "Попытка скачать чужое вложение"),
            @ApiResponse(responseCode = "404", description = "Обращение или вложение не найдено")
    })
    @GetMapping("/{id}/attachment")
    public ResponseEntity<byte[]> downloadAttachment(
            @AuthenticationPrincipal AppUserPrincipal principal,
            @PathVariable("id") Long id
    ) {
        TicketService.AttachmentDownload download = ticketService.downloadAttachment(principal.getUser(), id);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(download.contentType()));
        headers.setContentDisposition(
                ContentDisposition.attachment()
                        .filename(download.fileName(), StandardCharsets.UTF_8)
                        .build()
        );

        return new ResponseEntity<>(download.content(), headers, HttpStatus.OK);
    }
}
