package com.qyuro.joinflow.server;

import com.qyuro.joinflow.config.ServerConfig;
import com.qyuro.joinflow.profiler.TickMetrics;

/** Cheap tick observer. It samples tick-start to tick-start so JoinFlow work is included. */
public final class GlobalLoadController {
    private final TickMetrics tickMetrics = new TickMetrics();
    private long tickCount;
    private long usedHeapBytes;
    private long maxHeapBytes;
    private long nextMemorySampleTick;

    public void onTickStart(long nowNanos) {
        tickMetrics.tickStart(nowNanos);
        tickCount++;
        if (tickCount >= nextMemorySampleTick) {
            Runtime runtime = Runtime.getRuntime();
            usedHeapBytes = runtime.totalMemory() - runtime.freeMemory();
            maxHeapBytes = runtime.maxMemory();
            nextMemorySampleTick = tickCount + 100;
        }
    }

    public LoadState classify() {
        double avg = Math.max(tickMetrics.averageRecentMs(), tickMetrics.emaMs());
        if (avg >= ServerConfig.emergencyMspt()) return LoadState.EMERGENCY;
        if (avg >= ServerConfig.criticalMspt()) return LoadState.CRITICAL;
        if (avg >= ServerConfig.protectedMspt()) return LoadState.PROTECTED;
        if (avg >= ServerConfig.normalMspt()) return LoadState.NORMAL;
        if (avg < ServerConfig.ecoMspt()) return LoadState.ECO;
        return LoadState.NORMAL;
    }

    public TickMetrics metrics() { return tickMetrics; }
    public long tickCount() { return tickCount; }
    public long usedHeapBytes() { return usedHeapBytes; }
    public long maxHeapBytes() { return maxHeapBytes; }
    public double lastMspt() { return tickMetrics.lastMs(); }
    public double averageMspt() { return tickMetrics.averageRecentMs(); }
    public double recentMaxMspt() { return tickMetrics.maxRecentMs(); }
    public double estimatedTps() { return tickMetrics.estimatedTps(); }
}
