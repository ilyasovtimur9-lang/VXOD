package com.qyuro.joinflow.server;

import com.qyuro.joinflow.config.ServerConfig;

/** Reduces optional JoinFlow work under pressure; it does not alter existing gameplay or packets. */
public final class ExistingPlayerProtection {
    public double joinMultiplier(LoadState state, int onlinePlayers, int activeJoinSessions) {
        double loadMultiplier = switch (state) {
            case ECO, NORMAL, RECOVERY -> 1.0;
            case PROTECTED -> 0.80;
            case CRITICAL -> 0.55;
            case EMERGENCY -> 0.30;
        };

        if (onlinePlayers <= 0 || activeJoinSessions <= ServerConfig.maxConcurrentJoins()) {
            return loadMultiplier;
        }
        double excess = activeJoinSessions - ServerConfig.maxConcurrentJoins();
        double sharing = 1.0 / Math.sqrt(1.0 + (0.35 * excess));
        return Math.max(0.20, loadMultiplier * sharing);
    }

    public double postJoinMultiplier(LoadState state) {
        return switch (state) {
            case ECO, NORMAL, RECOVERY -> 1.0;
            case PROTECTED -> 0.75;
            case CRITICAL -> 0.35;
            case EMERGENCY -> 0.0;
        };
    }
}
