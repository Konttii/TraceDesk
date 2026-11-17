package com.tracedesk.domain.enums;

public enum PasswordStrength {
    WEAK(1),
    MEDIUM(2),
    STRONG(3);

    private final int bars;

    PasswordStrength(int bars) {
        this.bars = bars;
    }

    public int getBars() {
        return bars;
    }
}
