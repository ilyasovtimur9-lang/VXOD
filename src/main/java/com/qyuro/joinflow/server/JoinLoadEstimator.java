package com.qyuro.joinflow.server;

/**
 * Conservative projection based on measured JoinFlow task costs and queue pressure. This is a
 * scheduling signal, not a claim that task cost is CPU percentage or an exact future MSPT.
 */
public final class JoinLoadEstimator {
    public double predictMspt(double measuredAverageMspt, int activeSessions, long queuedEstimatedMicros,
                              double averageTaskMicros, int maxConcurrentJoins) {
        if (activeSessions <= 0 && queuedEstimatedMicros <= 0L) return measuredAverageMspt;

        double activeCostPerTickMs = Math.max(0.0, averageTaskMicros) / 1000.0
                * Math.min(activeSessions, Math.max(1, maxConcurrentJoins)) * 0.05;
        double queueCostPerTickMs = Math.min(2.0, Math.max(0L, queuedEstimatedMicros) / 1_000_000.0 * 0.02);
        double projectedExtra = Math.min(4.0, activeCostPerTickMs + queueCostPerTickMs);
        return Math.max(measuredAverageMspt, measuredAverageMspt + projectedExtra);
    }

    public LoadState projectedState(double projectedMspt) {
        if (projectedMspt >= com.qyuro.joinflow.config.ServerConfig.emergencyMspt()) return LoadState.EMERGENCY;
        if (projectedMspt >= com.qyuro.joinflow.config.ServerConfig.criticalMspt()) return LoadState.CRITICAL;
        if (projectedMspt >= com.qyuro.joinflow.config.ServerConfig.protectedMspt()) return LoadState.PROTECTED;
        if (projectedMspt >= com.qyuro.joinflow.config.ServerConfig.normalMspt()) return LoadState.NORMAL;
        return projectedMspt < com.qyuro.joinflow.config.ServerConfig.ecoMspt() ? LoadState.ECO : LoadState.NORMAL;
    }
}
