package com.qyuro.joinflow.profiler;

import com.qyuro.joinflow.config.ServerConfig;
import com.qyuro.joinflow.learning.JoinLearningModel;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** JoinFlow-only measurements. Third-party phases are labelled observed, never attributed by guess. */
public final class JoinProfiler {
    private final JoinLearningModel learningModel = new JoinLearningModel();
    private final ArrayDeque<JoinRecord> recentJoins = new ArrayDeque<>();
    private final ArrayDeque<TaskTiming> recentTaskTimings = new ArrayDeque<>();
    private long totalJoins;
    private double durationTotalMs;
    private double maximumDurationMs;
    private long emergencyCount;

    public void recordTask(TaskTiming timing) {
        if (ServerConfig.profilingMode().equals("OFF")) return;
        learningModel.record(timing.taskId(), Math.max(0L, (timing.endedNanos() - timing.startedNanos()) / 1000L));
        if (ServerConfig.profilingMode().equals("FULL")) {
            recentTaskTimings.addLast(timing);
            while (recentTaskTimings.size() > 1024) recentTaskTimings.removeFirst();
        }
    }

    public void recordJoin(JoinRecord record) {
        totalJoins++;
        durationTotalMs += record.durationMs();
        maximumDurationMs = Math.max(maximumDurationMs, record.durationMs());
        if (record.loadState().equals("EMERGENCY")) emergencyCount++;
        recentJoins.addLast(record);
        trimHistory();
    }

    public void restore(List<JoinRecord> records) {
        List<JoinRecord> combined = new ArrayList<>(records.size() + recentJoins.size());
        combined.addAll(records);
        combined.addAll(recentJoins);
        combined.sort(Comparator.comparingLong(JoinRecord::timestampMillis));

        for (JoinRecord record : records) {
            totalJoins++;
            durationTotalMs += record.durationMs();
            maximumDurationMs = Math.max(maximumDurationMs, record.durationMs());
            if (record.loadState().equals("EMERGENCY")) emergencyCount++;
        }
        recentJoins.clear();
        recentJoins.addAll(combined);
        trimHistory();
    }

    private void trimHistory() {
        int limit = ServerConfig.historySize();
        while (recentJoins.size() > limit) recentJoins.removeFirst();
    }

    public void clearHistory() {
        recentJoins.clear();
        totalJoins = 0L;
        durationTotalMs = 0.0;
        maximumDurationMs = 0.0;
        emergencyCount = 0L;
        learningModel.clear();
        recentTaskTimings.clear();
    }

    public List<JoinRecord> recentJoins() {
        return List.copyOf(recentJoins);
    }

    public List<JoinRecord> historySnapshot() {
        return new ArrayList<>(recentJoins);
    }

    public double averageJoinMs() {
        return totalJoins == 0L ? 0.0 : durationTotalMs / totalJoins;
    }

    public double p95JoinMs() {
        if (recentJoins.isEmpty()) return 0.0;
        List<Double> values = recentJoins.stream().map(JoinRecord::durationMs).sorted(Comparator.naturalOrder()).toList();
        int index = Math.min(values.size() - 1, (int) Math.ceil(values.size() * 0.95) - 1);
        return values.get(Math.max(0, index));
    }

    public long totalJoins() { return totalJoins; }
    public double maximumDurationMs() { return maximumDurationMs; }
    public long emergencyCount() { return emergencyCount; }
    public JoinLearningModel learningModel() { return learningModel; }
    public List<TaskTiming> recentTaskTimings() { return List.copyOf(recentTaskTimings); }

    public record JoinRecord(long timestampMillis, double durationMs, double observedPeakMspt,
                             int tasksCompleted, int tasksFailed, long bytesSent, long bytesReceived,
                             String loadState) {
        public JoinRecord {
            loadState = loadState == null ? "UNKNOWN" : loadState;
        }
    }
}
