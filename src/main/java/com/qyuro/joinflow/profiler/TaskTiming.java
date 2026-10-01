package com.qyuro.joinflow.profiler;

public record TaskTiming(String taskId, long queuedNanos, long startedNanos, long endedNanos,
                         long estimatedMicros, boolean success) {
    public double durationMs() {
        return Math.max(0L, endedNanos - startedNanos) / 1_000_000.0;
    }

    public double queueWaitMs() {
        return Math.max(0L, startedNanos - queuedNanos) / 1_000_000.0;
    }
}
