package com.qyuro.joinflow.task;

import com.qyuro.joinflow.profiler.TaskTiming;
import com.qyuro.joinflow.server.JoinSession;

public interface TaskExecutionObserver {
    void onTaskComplete(JoinSession session, TaskTiming timing, boolean success, Throwable failure);
    void onTaskDiscarded(JoinSession session, ScheduledTaskView task, String reason);

    record ScheduledTaskView(String taskId, long estimatedMicros, boolean synthetic) { }
}
