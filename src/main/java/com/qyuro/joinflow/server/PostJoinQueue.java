package com.qyuro.joinflow.server;

import com.qyuro.joinflow.task.JoinTask;
import com.qyuro.joinflow.task.JoinTaskScheduler;
import com.qyuro.joinflow.task.TaskExecutionObserver;
import com.qyuro.joinflow.task.TaskPriority;
import net.minecraft.server.MinecraftServer;

import java.util.Set;
import java.util.UUID;

/** Budgeted queue for explicitly safe post-login tasks; critical player/gameplay paths stay in Forge. */
public final class PostJoinQueue {
    private final JoinTaskScheduler scheduler = new JoinTaskScheduler();

    public boolean enqueue(JoinSession session, String taskId, TaskPriority priority, long estimateMicros,
                          Set<String> dependencies, JoinTask task, long tick) {
        return scheduler.enqueue(session, taskId, priority, estimateMicros, dependencies, task, tick);
    }

    public boolean enqueueSynthetic(UUID laneId, String taskId, TaskPriority priority, long estimateMicros,
                                    JoinTask task, long tick) {
        return scheduler.enqueueSynthetic(laneId, taskId, priority, estimateMicros, task, tick);
    }

    public int runRange(MinecraftServer server, JoinQueue sessions, double budgetMs,
                        TaskPriority minimum, TaskPriority maximum, long tick, int maxTasks,
                        TaskExecutionObserver observer) {
        if (budgetMs <= 0.0) return 0;
        long budgetNanos = (long) Math.min(Long.MAX_VALUE, budgetMs * 1_000_000.0);
        return scheduler.runBudget(server, sessions, budgetNanos, minimum, maximum, tick, maxTasks, observer);
    }

    public int cancelPlayer(UUID playerId, JoinSession session) {
        return scheduler.cancelPlayer(playerId, session);
    }

    public void cancelAll() { scheduler.cancelAll(); }
    public int size() { return scheduler.size(); }
    public long queuedEstimatedMicros() { return scheduler.queuedEstimatedMicros(); }
    public int pendingFor(UUID playerId) { return scheduler.pendingFor(playerId); }
}
