package com.tracedesk.service;

import com.tracedesk.domain.entity.Ticket;
import com.tracedesk.domain.entity.TicketReply;
import com.tracedesk.domain.entity.TicketStatusHistory;
import com.tracedesk.domain.entity.User;
import com.tracedesk.domain.enums.Role;
import com.tracedesk.domain.enums.TicketStatus;
import com.tracedesk.repository.TicketReplyRepository;
import com.tracedesk.repository.TicketRepository;
import com.tracedesk.repository.TicketStatusHistoryRepository;
import com.tracedesk.web.dto.*;
import com.tracedesk.web.exception.ApiException;
import com.tracedesk.web.exception.ErrorCode;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@Service
public class TicketService {

    private final TicketRepository ticketRepository;
    private final TicketReplyRepository replyRepository;
    private final TicketStatusHistoryRepository historyRepository;
    private final StorageService storageService;

    public TicketService(
            TicketRepository ticketRepository,
            TicketReplyRepository replyRepository,
            TicketStatusHistoryRepository historyRepository,
            StorageService storageService
    ) {
        this.ticketRepository = ticketRepository;
        this.replyRepository = replyRepository;
        this.historyRepository = historyRepository;
        this.storageService = storageService;
    }

    // 3.1 Создать обращение (только следователь INVESTIGATOR)
    @Transactional
    public TicketCreatedResponse createTicket(User currentUser, String title, String description, MultipartFile attachment) {
        requireInvestigatorRole(currentUser);

        if (title == null || title.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Тема обращения не может быть пустой");
        }
        if (description == null || description.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Описание обращения не может быть пустым");
        }

        Long seq = ticketRepository.getNextTicketSequenceValue();
        String ticketNumber = String.format("TD-%05d", seq);

        Ticket ticket = new Ticket();
        ticket.setTicketNumber(ticketNumber);
        ticket.setTitle(title.trim());
        ticket.setDescription(description.trim());
        ticket.setStatus(TicketStatus.OPEN);
        ticket.setAuthor(currentUser);

        if (attachment != null && !attachment.isEmpty()) {
            StorageService.StoredAttachment stored = storageService.uploadAttachment(attachment);
            ticket.setAttachmentKey(stored.key());
            ticket.setAttachmentName(stored.originalName());
            ticket.setAttachmentType(stored.contentType());
            ticket.setAttachmentSize(stored.size());
        }

        Ticket saved = ticketRepository.save(ticket);

        // Записываем создание в историю переходов (null -> OPEN)
        historyRepository.save(new TicketStatusHistory(saved, null, TicketStatus.OPEN, currentUser));

        return new TicketCreatedResponse(
                saved.getId(),
                saved.getTicketNumber(),
                saved.getStatus(),
                saved.getCreatedAt()
        );
    }

    // 3.2 и 3.4 Список обращений с фильтрацией по статусу и пагинацией
    @Transactional(readOnly = true)
    public Page<TicketSummaryResponse> listTickets(User currentUser, TicketStatus status, Pageable pageable) {
        Page<Ticket> page;
        if (currentUser.getRole() == Role.SUPPORT) {
            page = (status != null)
                    ? ticketRepository.findByStatus(status, pageable)
                    : ticketRepository.findAll(pageable);
        } else {
            page = (status != null)
                    ? ticketRepository.findByAuthorIdAndStatus(currentUser.getId(), status, pageable)
                    : ticketRepository.findByAuthorId(currentUser.getId(), pageable);
        }
        return page.map(this::toSummaryResponse);
    }

    // 3.4 Получить карточку обращения с ответами и историей
    @Transactional(readOnly = true)
    public TicketDetailsResponse getTicketDetails(User currentUser, Long ticketId) {
        Ticket ticket = getTicketOrThrow(ticketId);
        checkReadAccess(currentUser, ticket);
        return buildDetailsResponse(ticket);
    }

    // 3.2 Взять обращение в работу (OPEN -> IN_PROGRESS)
    @Transactional
    public TicketDetailsResponse assignTicket(User currentUser, Long ticketId) {
        requireSupportRole(currentUser);

        Ticket ticket = getTicketOrThrow(ticketId);
        if (ticket.getStatus() != TicketStatus.OPEN) {
            throw new ApiException(
                    ErrorCode.TICKET_ALREADY_ASSIGNED,
                    "Обращение не находится в статусе OPEN (текущий статус: " + ticket.getStatus() + ")"
            );
        }

        TicketStatus oldStatus = ticket.getStatus();
        ticket.setStatus(TicketStatus.IN_PROGRESS);
        ticket.setAssignee(currentUser);

        // saveAndFlush немедленно отправляет UPDATE ... WHERE id=? AND version=? в БД,
        // чтобы при конкурентном вызове второй поток сразу получил 409 Conflict
        Ticket saved = ticketRepository.saveAndFlush(ticket);
        historyRepository.save(new TicketStatusHistory(saved, oldStatus, TicketStatus.IN_PROGRESS, currentUser));

        return buildDetailsResponse(saved);
    }

    // 3.3 Добавить ответ оператора (только в состоянии IN_PROGRESS)
    @Transactional
    public ReplyResponse addReply(User currentUser, Long ticketId, String message) {
        requireSupportRole(currentUser);

        if (message == null || message.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Текст ответа не может быть пустым");
        }

        Ticket ticket = getTicketOrThrow(ticketId);
        if (ticket.getStatus() != TicketStatus.IN_PROGRESS) {
            throw new ApiException(
                    ErrorCode.INVALID_TICKET_STATUS,
                    "Ответить можно только на обращение в статусе IN_PROGRESS (текущий статус: " + ticket.getStatus() + ")"
            );
        }
        if (ticket.getAssignee() == null || !ticket.getAssignee().getId().equals(currentUser.getId())) {
            throw new ApiException(
                    ErrorCode.ACCESS_DENIED,
                    "Ответить на обращение может только назначенный на него оператор"
            );
        }

        TicketReply reply = replyRepository.save(new TicketReply(ticket, currentUser, message.trim()));
        return new ReplyResponse(
                reply.getId(),
                currentUser.getUsername(),
                currentUser.getFullName(),
                reply.getMessage(),
                reply.getCreatedAt()
        );
    }

    // 3.3 Закрыть обращение (IN_PROGRESS -> CLOSED, только после хотя бы одного ответа)
    @Transactional
    public TicketDetailsResponse closeTicket(User currentUser, Long ticketId) {
        requireSupportRole(currentUser);

        Ticket ticket = getTicketOrThrow(ticketId);
        if (ticket.getStatus() != TicketStatus.IN_PROGRESS) {
            throw new ApiException(
                    ErrorCode.INVALID_TICKET_STATUS,
                    "Закрыть можно только обращение в статусе IN_PROGRESS (текущий статус: " + ticket.getStatus() + ")"
            );
        }
        if (ticket.getAssignee() == null || !ticket.getAssignee().getId().equals(currentUser.getId())) {
            throw new ApiException(
                    ErrorCode.ACCESS_DENIED,
                    "Закрыть обращение может только назначенный на него оператор"
            );
        }
        if (!replyRepository.existsByTicketId(ticketId)) {
            throw new ApiException(
                    ErrorCode.CLOSE_WITHOUT_REPLY,
                    "Нельзя закрыть обращение без хотя бы одного ответа оператора"
            );
        }

        TicketStatus oldStatus = ticket.getStatus();
        ticket.setStatus(TicketStatus.CLOSED);

        Ticket saved = ticketRepository.saveAndFlush(ticket);
        historyRepository.save(new TicketStatusHistory(saved, oldStatus, TicketStatus.CLOSED, currentUser));

        return buildDetailsResponse(saved);
    }

    // 3.4 Скачать вложение
    @Transactional(readOnly = true)
    public AttachmentDownload downloadAttachment(User currentUser, Long ticketId) {
        Ticket ticket = getTicketOrThrow(ticketId);
        checkReadAccess(currentUser, ticket);

        if (ticket.getAttachmentKey() == null) {
            throw new ApiException(ErrorCode.ATTACHMENT_NOT_FOUND, "У данного обращения нет вложения");
        }

        byte[] content = storageService.downloadAttachment(ticket.getAttachmentKey());
        return new AttachmentDownload(
                ticket.getAttachmentName(),
                ticket.getAttachmentType(),
                content
        );
    }

    private Ticket getTicketOrThrow(Long ticketId) {
        return ticketRepository.findWithDetailsById(ticketId)
                .orElseThrow(() -> new ApiException(ErrorCode.TICKET_NOT_FOUND, "Обращение с ID " + ticketId + " не найдено"));
    }

    private void checkReadAccess(User currentUser, Ticket ticket) {
        if (currentUser.getRole() == Role.SUPPORT) {
            return;
        }
        if (!ticket.getAuthor().getId().equals(currentUser.getId())) {
            throw new ApiException(ErrorCode.ACCESS_DENIED, "Доступ к чужому обращению запрещён");
        }
    }

    private void requireSupportRole(User currentUser) {
        if (currentUser.getRole() != Role.SUPPORT) {
            throw new ApiException(ErrorCode.ACCESS_DENIED, "Операция доступна только операторам техподдержки (SUPPORT)");
        }
    }

    private void requireInvestigatorRole(User currentUser) {
        if (currentUser.getRole() != Role.INVESTIGATOR) {
            throw new ApiException(ErrorCode.ACCESS_DENIED, "Создавать обращения могут только следователи (INVESTIGATOR)");
        }
    }

    private TicketSummaryResponse toSummaryResponse(Ticket t) {
        return new TicketSummaryResponse(
                t.getId(),
                t.getTicketNumber(),
                t.getTitle(),
                t.getStatus(),
                t.getAuthor().getUsername(),
                t.getAuthor().getFullName(),
                t.getAssignee() != null ? t.getAssignee().getUsername() : null,
                t.getAssignee() != null ? t.getAssignee().getFullName() : null,
                t.getAttachmentKey() != null,
                t.getCreatedAt(),
                t.getUpdatedAt()
        );
    }

    private TicketDetailsResponse buildDetailsResponse(Ticket t) {
        List<ReplyResponse> replies = replyRepository.findByTicketIdOrderByCreatedAtAscIdAsc(t.getId())
                .stream()
                .map(r -> new ReplyResponse(
                        r.getId(),
                        r.getAuthor().getUsername(),
                        r.getAuthor().getFullName(),
                        r.getMessage(),
                        r.getCreatedAt()
                ))
                .toList();

        List<StatusHistoryResponse> history = historyRepository.findByTicketIdOrderByChangedAtAscIdAsc(t.getId())
                .stream()
                .map(h -> new StatusHistoryResponse(
                        h.getId(),
                        h.getOldStatus(),
                        h.getNewStatus(),
                        h.getChangedBy().getUsername(),
                        h.getChangedBy().getFullName(),
                        h.getChangedAt()
                ))
                .toList();

        return new TicketDetailsResponse(
                t.getId(),
                t.getTicketNumber(),
                t.getTitle(),
                t.getDescription(),
                t.getStatus(),
                t.getAuthor().getUsername(),
                t.getAuthor().getFullName(),
                t.getAssignee() != null ? t.getAssignee().getUsername() : null,
                t.getAssignee() != null ? t.getAssignee().getFullName() : null,
                t.getAttachmentName(),
                t.getAttachmentType(),
                t.getAttachmentSize(),
                t.getCreatedAt(),
                t.getUpdatedAt(),
                replies,
                history
        );
    }

    public record AttachmentDownload(
            String fileName,
            String contentType,
            byte[] content
    ) {
    }
}