package com.qyuro.joinflow.client;

/** Minimal client companion telemetry. No FPS, IP, file inventory or unrelated process data. */
public final class ClientTelemetry {
    private long lastBatchProcessingMicros;
    private int syncQueueDepth;
    private boolean ready;
    private boolean cacheReady;

    public void recordBatchProcessing(long elapsedNanos) {
        lastBatchProcessingMicros = Math.max(0L, elapsedNanos / 1000L);
    }

    public void setSyncQueueDepth(int depth) { syncQueueDepth = Math.max(0, Math.min(256, depth)); }
    public void setReady(boolean ready) { this.ready = ready; }
    public void setCacheReady(boolean cacheReady) { this.cacheReady = cacheReady; }

    public long lastBatchProcessingMicros() { return lastBatchProcessingMicros; }
    public int syncQueueDepth() { return syncQueueDepth; }
    public boolean ready() { return ready; }
    public boolean cacheReady() { return cacheReady; }
}
