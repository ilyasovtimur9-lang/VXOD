package com.qyuro.joinflow.task;

import java.util.concurrent.atomic.AtomicBoolean;

/** Cooperative cancellation for JoinFlow-owned work. It never interrupts a running task. */
public final class CancellationToken {
    private final AtomicBoolean cancelled = new AtomicBoolean();

    public boolean isCancelled() {
        return cancelled.get();
    }

    public boolean cancel() {
        return cancelled.compareAndSet(false, true);
    }
}
