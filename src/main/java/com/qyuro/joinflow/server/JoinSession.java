package com.qyuro.joinflow.server;

import com.qyuro.joinflow.api.JoinPhase;
import com.qyuro.joinflow.task.CancellationToken;
import com.qyuro.joinflow.task.TaskPriority;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Compact active-session state. It intentionally stores no ServerPlayer, Level or Entity. */
public final class JoinSession {
    private static final int MAX_COMPLETED_PROVIDER_IDS = 128;
    private final UUID playerId;
    private final long startNanos;
    private final CancellationToken cancellationToken = new CancellationToken();
    private final Set<String> completedTaskIds = new HashSet<>();
    private final Map<String, PendingSync> pendingSyncs = new HashMap<>();
    private long totalEstimatedMicros;
    private long remainingEstimatedMicros;
    private long completedEstimatedMicros;
    private int tasksPending;
    private int tasksCompleted;
    private int tasksFailed;
    private long bytesSent;
    private long bytesReceived;
    private long lastClientHeartbeatNanos;
    private long telemetryWindowStartNanos;
    private int telemetryPacketsInWindow;
    private int lastClientSequence = -1;
    private int serverSequence;
    private int clientQueueDepth;
    private long lastClientProcessingMicros;
    private boolean clientChannelPresent;
    private boolean clientSupported;
    private boolean clientReady;
    private JoinPhase phase = JoinPhase.POST_JOIN;
    private TaskPriority priority = TaskPriority.NORMAL;
    private State state = State.ACTIVE;

    public JoinSession(UUID playerId, long nowNanos) {
        this.playerId = playerId;
        this.startNanos = nowNanos;
        this.lastClientHeartbeatNanos = nowNanos;
    }

    public void taskQueued(String taskId, long estimatedMicros) {
        long weight = Math.max(1L, estimatedMicros);
        totalEstimatedMicros = saturatedAdd(totalEstimatedMicros, weight);
        remainingEstimatedMicros = saturatedAdd(remainingEstimatedMicros, weight);
        tasksPending++;
    }

    public void taskFinished(String taskId, long estimatedMicros, long actualMicros, boolean success) {
        long safeWeight = Math.max(1L, estimatedMicros);
        remainingEstimatedMicros = Math.max(0L, remainingEstimatedMicros - safeWeight);
        completedEstimatedMicros = saturatedAdd(completedEstimatedMicros, safeWeight);
        tasksPending = Math.max(0, tasksPending - 1);
        tasksCompleted++;
        if (success && taskId != null && taskId.indexOf(':') > 0
                && completedTaskIds.size() < MAX_COMPLETED_PROVIDER_IDS) {
            completedTaskIds.add(taskId);
        } else if (!success) {
            tasksFailed++;
        }
    }

    public void taskCancelled(String taskId, long estimatedMicros) {
        remainingEstimatedMicros = Math.max(0L, remainingEstimatedMicros - Math.max(1L, estimatedMicros));
        tasksPending = Math.max(0, tasksPending - 1);
    }

    public boolean dependencyCompleted(String dependencyId) {
        return completedTaskIds.contains(dependencyId);
    }

    public void setPhase(JoinPhase phase) { this.phase = phase; }
    public void setPriority(TaskPriority priority) { this.priority = priority; }
    public void setClientChannelPresent(boolean present) { clientChannelPresent = present; }
    public void setClientSupported(boolean supported) { clientSupported = supported; }
    public void setClientReady(boolean ready) { clientReady = ready; }
    public void setClientStatus(int queueDepth, long processingMicros) {
        clientQueueDepth = queueDepth;
        lastClientProcessingMicros = processingMicros;
    }
    public void recordBytesSent(long bytes) { bytesSent = saturatedAdd(bytesSent, Math.max(0L, bytes)); }
    public void recordBytesReceived(long bytes) { bytesReceived = saturatedAdd(bytesReceived, Math.max(0L, bytes)); }
    public void heartbeat(long nowNanos) { lastClientHeartbeatNanos = nowNanos; }

    public boolean acceptTelemetry(long nowNanos, int maxPacketsPerSecond) {
        if (nowNanos - telemetryWindowStartNanos >= 1_000_000_000L || telemetryWindowStartNanos == 0L) {
            telemetryWindowStartNanos = nowNanos;
            telemetryPacketsInWindow = 0;
        }
        if (telemetryPacketsInWindow >= maxPacketsPerSecond) return false;
        telemetryPacketsInWindow++;
        return true;
    }

    public boolean acceptClientSequence(int sequence) {
        if (sequence < 0 || sequence <= lastClientSequence || sequence > lastClientSequence + 10000) return false;
        lastClientSequence = sequence;
        return true;
    }

    public int nextServerSequence() { return serverSequence++; }
    public void addPendingSync(String key, int version, String hash) {
        pendingSyncs.put(key, new PendingSync(version, hash));
    }
    public PendingSync pendingSync(String key) { return pendingSyncs.get(key); }
    public void clearPendingSync(String key) { pendingSyncs.remove(key); }
    public Map<String, PendingSync> pendingSyncsView() { return Map.copyOf(pendingSyncs); }

    public void markComplete() {
        state = State.COMPLETE;
        phase = JoinPhase.COMPLETE;
        cancellationToken.cancel();
    }

    public void cancel() {
        state = State.CANCELLED;
        cancellationToken.cancel();
        pendingSyncs.clear();
    }

    public void markTimedOut() {
        state = State.TIMED_OUT;
        cancellationToken.cancel();
        pendingSyncs.clear();
    }

    public UUID playerId() { return playerId; }
    public long startNanos() { return startNanos; }
    public double durationMs(long nowNanos) { return Math.max(0L, nowNanos - startNanos) / 1_000_000.0; }
    public CancellationToken cancellationToken() { return cancellationToken; }
    public int tasksPending() { return tasksPending; }
    public int tasksCompleted() { return tasksCompleted; }
    public int tasksFailed() { return tasksFailed; }
    public long totalEstimatedMicros() { return totalEstimatedMicros; }
    public long remainingEstimatedMicros() { return remainingEstimatedMicros; }
    public long completedEstimatedMicros() { return completedEstimatedMicros; }
    public long bytesSent() { return bytesSent; }
    public long bytesReceived() { return bytesReceived; }
    public long lastClientHeartbeatNanos() { return lastClientHeartbeatNanos; }
    public int clientQueueDepth() { return clientQueueDepth; }
    public long lastClientProcessingMicros() { return lastClientProcessingMicros; }
    public boolean clientChannelPresent() { return clientChannelPresent; }
    public boolean clientSupported() { return clientSupported; }
    public boolean clientReady() { return clientReady; }
    public JoinPhase phase() { return phase; }
    public TaskPriority priority() { return priority; }
    public State state() { return state; }

    public double progress() {
        if (totalEstimatedMicros <= 0L) return state == State.COMPLETE ? 1.0 : 0.0;
        return Math.max(0.0, Math.min(1.0, completedEstimatedMicros / (double) totalEstimatedMicros));
    }

    private static long saturatedAdd(long first, long second) {
        if (second > 0L && first > Long.MAX_VALUE - second) return Long.MAX_VALUE;
        return first + second;
    }

    public enum State { ACTIVE, COMPLETE, CANCELLED, TIMED_OUT }

    public record PendingSync(int version, String hash) { }
}
