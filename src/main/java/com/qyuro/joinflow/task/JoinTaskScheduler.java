package com.qyuro.joinflow.task;

import com.qyuro.joinflow.config.ServerConfig;
import com.qyuro.joinflow.profiler.TaskTiming;
import com.qyuro.joinflow.server.JoinQueue;
import com.qyuro.joinflow.server.JoinSession;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Bounded round-robin scheduler with per-priority queues and task aging. All methods run server-side. */
public final class JoinTaskScheduler {
    private static final int AGING_CHECKS_PER_PRIORITY = 8;
    private final EnumMap<TaskPriority, PriorityBucket> buckets = new EnumMap<>(TaskPriority.class);
    private final Map<UUID, Integer> pendingByPlayer = new HashMap<>();
    private int size;
    private long queuedEstimatedMicros;

    public JoinTaskScheduler() {
        for (TaskPriority priority : TaskPriority.values()) buckets.put(priority, new PriorityBucket());
    }

    public boolean enqueue(JoinSession session, String taskId, TaskPriority priority, long estimatedMicros,
                          Set<String> dependencies, JoinTask task, long tick) {
        return enqueueInternal(session, session.playerId(), taskId, priority, estimatedMicros,
                dependencies, task, tick, false);
    }

    public boolean enqueueSynthetic(UUID laneId, String taskId, TaskPriority priority, long estimatedMicros,
                                   JoinTask task, long tick) {
        return enqueueInternal(null, laneId, taskId, priority, estimatedMicros, Set.of(), task, tick, true);
    }

    private boolean enqueueInternal(JoinSession session, UUID playerId, String taskId, TaskPriority priority,
                                    long estimatedMicros, Set<String> dependencies, JoinTask task,
                                    long tick, boolean synthetic) {
        if (priority == null || task == null || taskId == null || playerId == null) return false;
        int perPlayerLimit = ServerConfig.maxTasksPerPlayer();
        if (size >= ServerConfig.maxTotalTasks()
                || pendingByPlayer.getOrDefault(playerId, 0) >= perPlayerLimit) {
            return false;
        }
        long estimate = Math.max(1L, estimatedMicros);
        ScheduledTask scheduled = new ScheduledTask(playerId, taskId, priority, estimate,
                dependencies == null ? Set.of() : Set.copyOf(dependencies), task,
                session == null ? new CancellationToken() : session.cancellationToken(), tick,
                System.nanoTime(), synthetic);
        if (session != null) session.taskQueued(taskId, estimate);
        buckets.get(priority).add(scheduled);
        pendingByPlayer.merge(playerId, 1, Integer::sum);
        size++;
        queuedEstimatedMicros = saturatedAdd(queuedEstimatedMicros, estimate);
        return true;
    }

    public int runBudget(MinecraftServer server, JoinQueue sessions, long budgetNanos,
                         TaskPriority minimumPriority, TaskPriority maximumPriority,
                         long tick, int maxTasks, TaskExecutionObserver observer) {
        if (size == 0 || budgetNanos <= 0L || maxTasks <= 0) return 0;
        promoteAged(tick);
        long startNanos = System.nanoTime();
        int considered = 0;

        while (considered < maxTasks && size > 0) {
            ScheduledTask task = poll(minimumPriority, maximumPriority, sessions);
            if (task == null) break;

            long elapsed = System.nanoTime() - startNanos;
            considered++;
            if (elapsed >= budgetNanos) {
                requeueFront(task);
                break;
            }

            JoinSession session = sessions.get(task.playerId);
            if (!task.synthetic && (session == null || task.cancellationToken.isCancelled())) {
                discard(task, session, observer, "session is no longer active");
                continue;
            }

            ServerPlayer player = task.synthetic ? null : server.getPlayerList().getPlayer(task.playerId);
            if (!task.synthetic && player == null) {
                discard(task, session, observer, "player is no longer connected");
                continue;
            }

            long taskStart = System.nanoTime();
            boolean success = false;
            Throwable failure = null;
            try {
                task.action.run(new JoinTaskContext(task.playerId, player, task.phase,
                        task.cancellationToken, task.synthetic));
                success = true;
            } catch (Throwable throwable) {
                if (throwable instanceof VirtualMachineError error) throw error;
                if (throwable instanceof ThreadDeath death) throw death;
                failure = throwable;
            }
            long taskEnd = System.nanoTime();
            TaskTiming timing = new TaskTiming(task.taskId, task.queuedNanos, taskStart, taskEnd,
                    task.estimatedMicros, success);
            observer.onTaskComplete(session, timing, success, failure);

            if (System.nanoTime() - startNanos >= budgetNanos) break;
        }
        return considered;
    }

    private ScheduledTask poll(TaskPriority minimum, TaskPriority maximum, JoinQueue sessions) {
        for (TaskPriority priority : TaskPriority.values()) {
            if (priority.rank() < minimum.rank() || priority.rank() > maximum.rank()) continue;
            ScheduledTask task = buckets.get(priority).pollEligible(candidate -> {
                if (candidate.synthetic) return true;
                JoinSession session = sessions.get(candidate.playerId);
                if (session == null || candidate.cancellationToken.isCancelled()) return true;
                for (String dependency : candidate.dependencies) {
                    if (!session.dependencyCompleted(dependency)) return false;
                }
                return true;
            });
            if (task != null) {
                size--;
                queuedEstimatedMicros = Math.max(0L, queuedEstimatedMicros - task.estimatedMicros);
                decrementPlayer(task.playerId);
                return task;
            }
        }
        return null;
    }

    private void promoteAged(long tick) {
        int interval = ServerConfig.agingIntervalTicks();
        promoteFrom(TaskPriority.BACKGROUND, tick, interval);
        promoteFrom(TaskPriority.LOW, tick, interval);
        promoteFrom(TaskPriority.NORMAL, tick, interval);
    }

    private void promoteFrom(TaskPriority priority, long tick, int interval) {
        PriorityBucket source = buckets.get(priority);
        PriorityBucket destination = buckets.get(priority.agedPromotion());
        if (destination == source) return;
        for (int checked = 0; checked < AGING_CHECKS_PER_PRIORITY; checked++) {
            ScheduledTask candidate = source.peekNext();
            if (candidate == null) return;
            source.rotateNextPlayer();
            if (tick - candidate.queuedTick < interval) continue;
            ScheduledTask promoted = source.removeTask(candidate);
            if (promoted != null) {
                promoted.priority = priority.agedPromotion();
                promoted.queuedTick = tick;
                destination.add(promoted);
            }
        }
    }

    private void requeueFront(ScheduledTask task) {
        buckets.get(task.priority).addFront(task);
        pendingByPlayer.merge(task.playerId, 1, Integer::sum);
        size++;
        queuedEstimatedMicros = saturatedAdd(queuedEstimatedMicros, task.estimatedMicros);
    }

    private void discard(ScheduledTask task, JoinSession session, TaskExecutionObserver observer, String reason) {
        if (session != null) session.taskCancelled(task.taskId, task.estimatedMicros);
        observer.onTaskDiscarded(session, new TaskExecutionObserver.ScheduledTaskView(task.taskId,
                task.estimatedMicros, task.synthetic), reason);
    }

    private void decrementPlayer(UUID playerId) {
        int remaining = pendingByPlayer.getOrDefault(playerId, 0) - 1;
        if (remaining <= 0) pendingByPlayer.remove(playerId);
        else pendingByPlayer.put(playerId, remaining);
    }

    public int cancelPlayer(UUID playerId, JoinSession session) {
        int removed = 0;
        for (PriorityBucket bucket : buckets.values()) {
            List<ScheduledTask> tasks = bucket.removePlayer(playerId);
            for (ScheduledTask task : tasks) {
                task.cancellationToken.cancel();
                size = Math.max(0, size - 1);
                queuedEstimatedMicros = Math.max(0L, queuedEstimatedMicros - task.estimatedMicros);
                decrementPlayer(playerId);
                if (session != null) session.taskCancelled(task.taskId, task.estimatedMicros);
                removed++;
            }
        }
        return removed;
    }

    public void cancelAll() {
        for (PriorityBucket bucket : buckets.values()) bucket.clear();
        pendingByPlayer.clear();
        size = 0;
        queuedEstimatedMicros = 0L;
    }

    public int size() { return size; }
    public long queuedEstimatedMicros() { return queuedEstimatedMicros; }
    public int pendingFor(UUID playerId) { return pendingByPlayer.getOrDefault(playerId, 0); }

    private static long saturatedAdd(long first, long second) {
        if (second > 0L && first > Long.MAX_VALUE - second) return Long.MAX_VALUE;
        return first + second;
    }

    private static final class ScheduledTask {
        private final UUID playerId;
        private final String taskId;
        private TaskPriority priority;
        private final long estimatedMicros;
        private final Set<String> dependencies;
        private final JoinTask action;
        private final CancellationToken cancellationToken;
        private long queuedTick;
        private final long queuedNanos;
        private final boolean synthetic;
        private final com.qyuro.joinflow.api.JoinPhase phase = com.qyuro.joinflow.api.JoinPhase.POST_JOIN;

        private ScheduledTask(UUID playerId, String taskId, TaskPriority priority, long estimatedMicros,
                              Set<String> dependencies, JoinTask action, CancellationToken token,
                              long queuedTick, long queuedNanos, boolean synthetic) {
            this.playerId = playerId;
            this.taskId = taskId;
            this.priority = priority;
            this.estimatedMicros = estimatedMicros;
            this.dependencies = dependencies;
            this.action = action;
            this.cancellationToken = token;
            this.queuedTick = queuedTick;
            this.queuedNanos = queuedNanos;
            this.synthetic = synthetic;
        }
    }

    private static final class PriorityBucket {
        private final LinkedHashMap<UUID, ArrayDeque<ScheduledTask>> byPlayer = new LinkedHashMap<>();
        private final ArrayDeque<UUID> rotation = new ArrayDeque<>();

        private void add(ScheduledTask task) {
            ArrayDeque<ScheduledTask> tasks = byPlayer.get(task.playerId);
            if (tasks == null) {
                tasks = new ArrayDeque<>();
                byPlayer.put(task.playerId, tasks);
                rotation.addLast(task.playerId);
            }
            tasks.addLast(task);
        }

        private void addFront(ScheduledTask task) {
            ArrayDeque<ScheduledTask> tasks = byPlayer.get(task.playerId);
            if (tasks == null) {
                tasks = new ArrayDeque<>();
                byPlayer.put(task.playerId, tasks);
                rotation.addFirst(task.playerId);
            }
            tasks.addFirst(task);
        }

        private ScheduledTask pollEligible(java.util.function.Predicate<ScheduledTask> predicate) {
            int checks = rotation.size();
            for (int i = 0; i < checks; i++) {
                UUID player = rotation.removeFirst();
                ArrayDeque<ScheduledTask> tasks = byPlayer.get(player);
                if (tasks == null || tasks.isEmpty()) {
                    byPlayer.remove(player);
                    continue;
                }
                ScheduledTask head = tasks.peekFirst();
                rotation.addLast(player);
                if (!predicate.test(head)) continue;
                tasks.removeFirst();
                if (tasks.isEmpty()) {
                    byPlayer.remove(player);
                    rotation.removeLastOccurrence(player);
                }
                return head;
            }
            return null;
        }

        private ScheduledTask peekNext() {
            UUID player = rotation.peekFirst();
            if (player == null) return null;
            ArrayDeque<ScheduledTask> tasks = byPlayer.get(player);
            return tasks == null ? null : tasks.peekFirst();
        }

        private void rotateNextPlayer() {
            UUID player = rotation.pollFirst();
            if (player != null && byPlayer.containsKey(player)) rotation.addLast(player);
        }

        private ScheduledTask removeTask(ScheduledTask target) {
            ArrayDeque<ScheduledTask> tasks = byPlayer.get(target.playerId);
            if (tasks == null || !tasks.removeFirstOccurrence(target)) return null;
            if (tasks.isEmpty()) {
                byPlayer.remove(target.playerId);
                rotation.removeFirstOccurrence(target.playerId);
            }
            return target;
        }

        private List<ScheduledTask> removePlayer(UUID playerId) {
            ArrayDeque<ScheduledTask> tasks = byPlayer.remove(playerId);
            rotation.removeIf(playerId::equals);
            return tasks == null ? List.of() : new ArrayList<>(tasks);
        }

        private void clear() {
            byPlayer.clear();
            rotation.clear();
        }
    }
}
