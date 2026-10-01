package com.qyuro.joinflow.profiler;

/** Fixed-size tick-time window. The hot path uses a primitive ring and allocates nothing. */
public final class TickMetrics {
    private static final int CAPACITY = 100;
    private static final int WINDOW = 40;

    private final double[] samplesMs = new double[CAPACITY];
    private int next;
    private int count;
    private long tickStartedNanos;
    private double emaMs;
    private double lastMs;

    public void tickStart(long nowNanos) {
        if (tickStartedNanos != 0L) {
            long elapsed = nowNanos - tickStartedNanos;
            if (elapsed > 0L) {
                record(elapsed / 1_000_000.0);
            }
        }
        tickStartedNanos = nowNanos;
    }

    private void record(double elapsedMs) {
        lastMs = elapsedMs;
        samplesMs[next] = elapsedMs;
        next = (next + 1) % CAPACITY;
        if (count < CAPACITY) count++;
        emaMs = count == 1 ? elapsedMs : (emaMs * 0.80) + (elapsedMs * 0.20);
    }

    public long sampleCount() {
        return count;
    }

    public double lastMs() {
        return lastMs;
    }

    public double emaMs() {
        return emaMs;
    }

    public double averageRecentMs() {
        int used = Math.min(count, WINDOW);
        if (used == 0) return 0.0;
        double sum = 0.0;
        for (int i = 0; i < used; i++) {
            int index = Math.floorMod(next - 1 - i, CAPACITY);
            sum += samplesMs[index];
        }
        return sum / used;
    }

    public double maxRecentMs() {
        int used = Math.min(count, WINDOW);
        double max = 0.0;
        for (int i = 0; i < used; i++) {
            int index = Math.floorMod(next - 1 - i, CAPACITY);
            max = Math.max(max, samplesMs[index]);
        }
        return max;
    }

    public int recentSpikeCount(double thresholdMs) {
        int used = Math.min(count, WINDOW);
        int spikes = 0;
        for (int i = 0; i < used; i++) {
            int index = Math.floorMod(next - 1 - i, CAPACITY);
            if (samplesMs[index] >= thresholdMs) spikes++;
        }
        return spikes;
    }

    public double estimatedTps() {
        double average = averageRecentMs();
        return average <= 0.0 ? 20.0 : Math.min(20.0, 1000.0 / average);
    }
}
