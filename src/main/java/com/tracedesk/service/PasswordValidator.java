package com.tracedesk.service;

import com.tracedesk.domain.enums.PasswordStrength;
import com.tracedesk.web.exception.ApiException;
import com.tracedesk.web.exception.ErrorCode;
import org.springframework.stereotype.Component;

@Component
public class PasswordValidator {

    public boolean isValid(String password) {
        if (password == null || password.length() < 8) {
            return false;
        }

        boolean hasLower = false;
        boolean hasUpper = false;
        boolean hasDigit = false;

        for (int i = 0; i < password.length(); i++) {
            char ch = password.charAt(i);
            if (Character.isLowerCase(ch)) {
                hasLower = true;
            } else if (Character.isUpperCase(ch)) {
                hasUpper = true;
            } else if (Character.isDigit(ch)) {
                hasDigit = true;
            }
        }

        return hasLower && hasUpper && hasDigit;
    }

    public void validateOrThrow(String password) {
        if (!isValid(password)) {
            throw new ApiException(
                    ErrorCode.VALIDATION_ERROR,
                    "Пароль должен содержать минимум 8 символов, заглавные и строчные буквы, а также цифры"
            );
        }
    }

    public PasswordStrength evaluateStrength(String password) {
        if (!isValid(password)) {
            return PasswordStrength.WEAK;
        }

        boolean hasSpecial = false;
        for (int i = 0; i < password.length(); i++) {
            char ch = password.charAt(i);
            if (!Character.isLetterOrDigit(ch) && !Character.isWhitespace(ch)) {
                hasSpecial = true;
                break;
            }
        }

        if (password.length() >= 10 && hasSpecial) {
            return PasswordStrength.STRONG;
        }
        return PasswordStrength.MEDIUM;
    }
}
