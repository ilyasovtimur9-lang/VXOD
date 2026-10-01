package com.qyuro.joinflow.learning;

import java.util.HashMap;
import java.util.Map;

/** Bounded, constant-space EMA and logarithmic histogram model for JoinFlow-owned task types. */
public final class JoinLearningModel {
    private static final int MAX_TASK_TYPES = 128;
    private final Map<String, TaskStatistics> statistics = new HashMap<>();

    public void record(String taskType, long durationMicros) {
        String key = taskType == null || taskType.isBlank() ? "unknown" : taskType;
        if (!statistics.containsKey(key) && statistics.size() >= MAX_TASK_TYPES - 1) key = "other";
        statistics.computeIfAbsent(key, ignored -> new TaskStatistics()).record(Math.max(0L, durationMicros));
    }

    public TaskSummary summary(String taskType) {
        TaskStatistics value = statistics.get(taskType);
        return value == null ? TaskSummary.empty(taskType) : value.summary(taskType);
    }

    public Map<String, TaskSummary> snapshot() {
        Map<String, TaskSummary> result = new HashMap<>();
        statistics.forEach((key, value) -> result.put(key, value.summary(key)));
        return Map.copyOf(result);
    }

    public double averageMicros() {
        if (statistics.isEmpty()) return 1000.0;
        double sum = 0.0;
        int count = 0;
        for (TaskStatistics value : statistics.values()) {
            if (value.count > 0L) {
                sum += value.emaMicros;
                count++;
            }
        }
        return count == 0 ? 1000.0 : sum / count;
    }

    public void clear() {
        statistics.clear();
    }

    private static final class TaskStatistics {
        private final long[] histogram = new long[32];
        private long count;
        private long totalMicros;
        private long maxMicros;
        private long lastMicros;
        private double emaMicros;

        private void record(long micros) {
            count++;
            totalMicros = saturatedAdd(totalMicros, micros);
            maxMicros = Math.max(maxMicros, micros);
            lastMicros = micros;
            emaMicros = count == 1 ? micros : emaMicros * 0.85 + micros * 0.15;
            int bucket = micros <= 1L ? 0 : Math.min(histogram.length - 1, 63 - Long.numberOfLeadingZeros(micros));
            histogram[bucket]++;
        }

        private TaskSummary summary(String taskType) {
            return new TaskSummary(taskType, count, count == 0 ? 0.0 : totalMicros / (double) count,
                    emaMicros, percentileMicros(0.50), percentileMicros(0.95), maxMicros, lastMicros);
        }

        private long percentileMicros(double percentile) {
            if (count == 0L) return 0L;
            long target = Math.max(1L, (long) Math.ceil(count * percentile));
            long accumulated = 0L;
            for (int i = 0; i < histogram.length; i++) {
                accumulated += histogram[i];
                if (accumulated >= target) {
                    return i == 0 ? 1L : (1L << (i + 1)) - 1L;
                }
            }
            return maxMicros;
        }
    }

    private static long saturatedAdd(long left, long right) {
        if (right > 0L && left > Long.MAX_VALUE - right) return Long.MAX_VALUE;
        return left + right;
    }

    public record TaskSummary(String taskType, long sampleCount, double averageMicros, double emaMicros,
                              long medianMicrosApprox, long p95MicrosApprox, long maxMicros, long recentMicros) {
        public static TaskSummary empty(String taskType) {
            return new TaskSummary(taskType, 0L, 0.0, 0.0, 0L, 0L, 0L, 0L);
        }
    }
}
