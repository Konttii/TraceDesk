package com.tracedesk.web.exception;

import org.springframework.http.HttpStatus;

public enum ErrorCode {

    VALIDATION_ERROR(HttpStatus.BAD_REQUEST),
    INVALID_FILE(HttpStatus.BAD_REQUEST),
    FILE_TOO_LARGE(HttpStatus.BAD_REQUEST),

    UNAUTHORIZED(HttpStatus.UNAUTHORIZED),
    ACCESS_DENIED(HttpStatus.FORBIDDEN),

    TICKET_NOT_FOUND(HttpStatus.NOT_FOUND),
    ATTACHMENT_NOT_FOUND(HttpStatus.NOT_FOUND),

    TICKET_ALREADY_ASSIGNED(HttpStatus.CONFLICT),
    INVALID_TICKET_STATUS(HttpStatus.CONFLICT),
    CLOSE_WITHOUT_REPLY(HttpStatus.CONFLICT),
    CONCURRENT_MODIFICATION(HttpStatus.CONFLICT),

    STORAGE_ERROR(HttpStatus.INTERNAL_SERVER_ERROR),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus status;

    ErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}