package com.tracedesk.web.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateReplyRequest(
        @NotBlank(message = "Текст ответа не может быть пустым")
        String message
) {
}
