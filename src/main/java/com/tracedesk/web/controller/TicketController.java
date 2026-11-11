package com.tracedesk.web.controller;

import com.tracedesk.config.AppUserPrincipal;
import com.tracedesk.domain.enums.TicketStatus;
import com.tracedesk.service.TicketService;
import com.tracedesk.web.dto.*;
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
public class TicketController {

    private final TicketService ticketService;

    public TicketController(TicketService ticketService) {
        this.ticketService = ticketService;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<TicketCreatedResponse> createTicket(
            @AuthenticationPrincipal AppUserPrincipal principal,
            @RequestParam(value = "title", required = false) String title,
            @RequestParam(value = "description", required = false) String description,
            @RequestPart(value = "file", required = false) MultipartFile file
    ) {
        TicketCreatedResponse created = ticketService.createTicket(
                principal.getUser(),
                title,
                description,
                file
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping
    public ResponseEntity<Page<TicketSummaryResponse>> listTickets(
            @AuthenticationPrincipal AppUserPrincipal principal,
            @RequestParam(value = "status", required = false) TicketStatus status,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "10") int size
    ) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return ResponseEntity.ok(ticketService.listTickets(principal.getUser(), status, pageable));
    }

    @GetMapping("/{id}")
    public ResponseEntity<TicketDetailsResponse> getTicket(
            @AuthenticationPrincipal AppUserPrincipal principal,
            @PathVariable("id") Long id
    ) {
        return ResponseEntity.ok(ticketService.getTicketDetails(principal.getUser(), id));
    }

    @PostMapping("/{id}/assign")
    public ResponseEntity<TicketDetailsResponse> assignTicket(
            @AuthenticationPrincipal AppUserPrincipal principal,
            @PathVariable("id") Long id
    ) {
        return ResponseEntity.ok(ticketService.assignTicket(principal.getUser(), id));
    }

    @PostMapping("/{id}/replies")
    public ResponseEntity<ReplyResponse> addReply(
            @AuthenticationPrincipal AppUserPrincipal principal,
            @PathVariable("id") Long id,
            @Valid @RequestBody CreateReplyRequest request
    ) {
        ReplyResponse reply = ticketService.addReply(principal.getUser(), id, request.message());
        return ResponseEntity.status(HttpStatus.CREATED).body(reply);
    }

    @PostMapping("/{id}/close")
    public ResponseEntity<TicketDetailsResponse> closeTicket(
            @AuthenticationPrincipal AppUserPrincipal principal,
            @PathVariable("id") Long id
    ) {
        return ResponseEntity.ok(ticketService.closeTicket(principal.getUser(), id));
    }

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
