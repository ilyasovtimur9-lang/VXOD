package com.qyuro.joinflow.server;

public enum LoadState {
    ECO(0),
    NORMAL(1),
    PROTECTED(2),
    CRITICAL(3),
    EMERGENCY(4),
    RECOVERY(-1);

    private final int severity;

    LoadState(int severity) {
        this.severity = severity;
    }

    public int severity() {
        return severity;
    }

    public static LoadState moreSevere(LoadState first, LoadState second) {
        if (first == RECOVERY) return second;
        if (second == RECOVERY) return first;
        return first.severity >= second.severity ? first : second;
    }
}
