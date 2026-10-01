package com.qyuro.joinflow.task;

import com.qyuro.joinflow.api.JoinPhase;

import java.util.Set;

/** Immutable integration descriptor. The task is shared and must not capture player/world objects. */
public record JoinTaskProvider(
        String ownerId,
        String taskId,
        JoinPhase phase,
        TaskPriority priority,
        long estimatedCostMicros,
        Set<String> dependencies,
        boolean safeToDefer,
        JoinTask task
) {
    public JoinTaskProvider {
        dependencies = Set.copyOf(dependencies);
    }

    public String qualifiedId() {
        return ownerId + ":" + taskId;
    }
}
