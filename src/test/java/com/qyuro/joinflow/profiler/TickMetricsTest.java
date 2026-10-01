package com.qyuro.joinflow.profiler;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TickMetricsTest {
    @Test
    void samplesStartToStartIntervalsAndCalculatesRecentStatistics() {
        TickMetrics metrics = new TickMetrics();
        long start = 1_000_000_000L;

        metrics.tickStart(start);
        metrics.tickStart(start + 50_000_000L);
        metrics.tickStart(start + 100_000_000L);
        metrics.tickStart(start + 1_100_000_000L);

        assertEquals(3L, metrics.sampleCount());
        assertEquals(1_000.0, metrics.lastMs(), 0.001);
        assertEquals((50.0 + 50.0 + 1_000.0) / 3.0, metrics.averageRecentMs(), 0.001);
        assertEquals(1_000.0, metrics.maxRecentMs(), 0.001);
        assertEquals(1000.0 / metrics.averageRecentMs(), metrics.estimatedTps(), 0.001);
    }

    @Test
    void ringWindowRetainsOnlyTheMostRecentFortySamplesForAverages() {
        TickMetrics metrics = new TickMetrics();
        long now = 1_000_000_000L;
        metrics.tickStart(now);

        for (int sample = 1; sample <= 45; sample++) {
            long intervalMs = sample <= 5 ? 100L : 20L;
            now += intervalMs * 1_000_000L;
            metrics.tickStart(now);
        }

        assertEquals(45L, metrics.sampleCount());
        assertEquals(20.0, metrics.averageRecentMs(), 0.001);
        assertEquals(20.0, metrics.maxRecentMs(), 0.001);
    }
}
