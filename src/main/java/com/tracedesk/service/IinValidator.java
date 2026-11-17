package com.tracedesk.service;

import com.tracedesk.web.exception.ApiException;
import com.tracedesk.web.exception.ErrorCode;
import org.springframework.stereotype.Component;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.regex.Pattern;

@Component
public class IinValidator {

    private static final Pattern TWELVE_DIGITS = Pattern.compile("^\\d{12}$");

    public boolean isValid(String iin) {
        if (iin == null || !TWELVE_DIGITS.matcher(iin).matches()) {
            return false;
        }

        int yy = Integer.parseInt(iin.substring(0, 2));
        int mm = Integer.parseInt(iin.substring(2, 4));
        int dd = Integer.parseInt(iin.substring(4, 6));
        int centuryDigit = Character.digit(iin.charAt(6), 10);

        int centuryBase = switch (centuryDigit) {
            case 1, 2 -> 1800;
            case 3, 4 -> 1900;
            case 5, 6 -> 2000;
            default -> (yy == 0) ? 2000 : 1900;
        };

        try {
            LocalDate.of(centuryBase + yy, mm, dd);
            return true;
        } catch (DateTimeException ex) {
            return false;
        }
    }

    public void validateOrThrow(String iin) {
        if (iin == null || !TWELVE_DIGITS.matcher(iin).matches()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "ИИН должен состоять ровно из 12 цифр");
        }
        if (!isValid(iin)) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Некорректная дата рождения в первых 6 цифрах ИИН (ГГММДД)");
        }
    }
}
