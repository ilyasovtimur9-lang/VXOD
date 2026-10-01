package com.qyuro.joinflow.task;

/** Ordered from most urgent to least urgent. CRITICAL cannot be reached by aging. */
public enum TaskPriority {
    CRITICAL(5),
    HIGH(4),
    NORMAL(3),
    LOW(2),
    BACKGROUND(1);

    private final int rank;

    TaskPriority(int rank) {
        this.rank = rank;
    }

    public int rank() {
        return rank;
    }

    public TaskPriority agedPromotion() {
        return switch (this) {
            case BACKGROUND -> LOW;
            case LOW -> NORMAL;
            case NORMAL -> HIGH;
            case HIGH, CRITICAL -> this;
        };
    }

    public boolean isAtLeast(TaskPriority minimum) {
        return rank >= minimum.rank;
    }
}
