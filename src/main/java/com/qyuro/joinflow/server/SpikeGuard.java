package com.qyuro.joinflow.server;

import com.qyuro.joinflow.config.ServerConfig;

/** Detects isolated and repeated tick spikes and holds a protective state during recovery. */
public final class SpikeGuard {
    private int consecutiveSpikes;
    private int protectionTicksRemaining;
    private LoadState heldState = LoadState.ECO;
    private long totalSpikes;

    public LoadState observe(double tickMspt) {
        if (!ServerConfig.spikeGuardEnabled()) {
            consecutiveSpikes = 0;
            protectionTicksRemaining = 0;
            heldState = LoadState.ECO;
            return LoadState.ECO;
        }
        if (tickMspt <= 0.0) return LoadState.ECO;

        if (tickMspt >= ServerConfig.spikeThresholdMs()) {
            consecutiveSpikes++;
            totalSpikes++;
            protectionTicksRemaining = Math.max(protectionTicksRemaining, ServerConfig.recoveryTicks());
            if (tickMspt >= ServerConfig.emergencyMspt()) {
                heldState = LoadState.EMERGENCY;
            } else if (tickMspt >= ServerConfig.criticalMspt()
                    || consecutiveSpikes >= ServerConfig.spikesBeforeCritical()) {
                heldState = LoadState.CRITICAL;
            } else {
                heldState = LoadState.PROTECTED;
            }
            return heldState;
        }

        consecutiveSpikes = 0;
        if (protectionTicksRemaining > 0) {
            protectionTicksRemaining--;
            return heldState;
        }
        heldState = LoadState.ECO;
        return LoadState.ECO;
    }

    public int consecutiveSpikes() { return consecutiveSpikes; }
    public long totalSpikes() { return totalSpikes; }
    public int protectionTicksRemaining() { return protectionTicksRemaining; }
}
