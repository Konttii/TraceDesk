package com.tracedesk.web.exception;

import org.springframework.http.HttpStatus;

public enum ErrorCode {

    VALIDATION_ERROR(HttpStatus.BAD_REQUEST),
    INVALID_FILE(HttpStatus.BAD_REQUEST),
    FILE_TOO_LARGE(HttpStatus.BAD_REQUEST),
    INVALID_OTP_CODE(HttpStatus.BAD_REQUEST),
    OTP_ATTEMPTS_EXCEEDED(HttpStatus.BAD_REQUEST),
    REGISTRATION_DRAFT_NOT_FOUND(HttpStatus.BAD_REQUEST),
    PASSWORD_RESET_NOT_FOUND(HttpStatus.BAD_REQUEST),
    SAME_PASSWORD(HttpStatus.BAD_REQUEST),

    UNAUTHORIZED(HttpStatus.UNAUTHORIZED),
    ACCESS_DENIED(HttpStatus.FORBIDDEN),
    ACCOUNT_PENDING_APPROVAL(HttpStatus.FORBIDDEN),
    ACCOUNT_BLOCKED(HttpStatus.FORBIDDEN),
    ACCOUNT_LOCKED(HttpStatus.LOCKED),

    TICKET_NOT_FOUND(HttpStatus.NOT_FOUND),
    ATTACHMENT_NOT_FOUND(HttpStatus.NOT_FOUND),
    USER_NOT_FOUND(HttpStatus.NOT_FOUND),

    TICKET_ALREADY_ASSIGNED(HttpStatus.CONFLICT),
    INVALID_TICKET_STATUS(HttpStatus.CONFLICT),
    INVALID_USER_STATUS(HttpStatus.CONFLICT),
    CLOSE_WITHOUT_REPLY(HttpStatus.CONFLICT),
    CONCURRENT_MODIFICATION(HttpStatus.CONFLICT),
    IIN_ALREADY_EXISTS(HttpStatus.CONFLICT),
    EMAIL_ALREADY_EXISTS(HttpStatus.CONFLICT),

    OTP_COOLDOWN_ACTIVE(HttpStatus.TOO_MANY_REQUESTS),
    EMAIL_VERIFICATION_BLOCKED(HttpStatus.TOO_MANY_REQUESTS),
    PASSWORD_RESET_BLOCKED(HttpStatus.TOO_MANY_REQUESTS),

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