package com.qyuro.joinflow.server;

import com.qyuro.joinflow.config.ServerConfig;

/** Computes a per-tick allowance for JoinFlow-owned work only. It is a cap, not a guarantee. */
public final class AdaptiveBudgetController {
    private double joinBudgetMs;
    private double postJoinBudgetMs;
    private double backgroundBudgetMs;

    public void update(LoadState state, double recoveryFactor) {
        double normalJoin = ServerConfig.joinNormalMs();
        double normalPost = ServerConfig.postNormalMs();
        double normalBackground = ServerConfig.backgroundNormalMs();

        if (!ServerConfig.enabled()) {
            set(0.0, 0.0, 0.0);
            return;
        }
        if (!ServerConfig.adaptiveEnabled()) {
            set(normalJoin, normalPost, normalBackground);
            return;
        }

        switch (state) {
            case ECO, NORMAL -> set(normalJoin, normalPost, normalBackground);
            case PROTECTED -> set(ServerConfig.joinProtectedMs(), ServerConfig.postProtectedMs(),
                    ServerConfig.backgroundProtectedMs());
            case CRITICAL -> set(ServerConfig.joinCriticalMs(), ServerConfig.postCriticalMs(), 0.0);
            case EMERGENCY -> set(ServerConfig.joinEmergencyMs(), 0.0, 0.0);
            case RECOVERY -> {
                double factor = clamp(recoveryFactor, 0.0, 1.0);
                set(interpolate(ServerConfig.joinEmergencyMs(), normalJoin, factor),
                        interpolate(0.0, normalPost, factor),
                        normalBackground * factor);
            }
        }

        double minimum = Math.min(ServerConfig.joinMinMs(), normalJoin);
        joinBudgetMs = Math.max(minimum, Math.min(normalJoin, joinBudgetMs));
        postJoinBudgetMs = Math.min(normalPost, postJoinBudgetMs);
        backgroundBudgetMs = Math.min(normalBackground, backgroundBudgetMs);
    }

    private void set(double join, double postJoin, double background) {
        joinBudgetMs = Math.max(0.0, join);
        postJoinBudgetMs = Math.max(0.0, postJoin);
        backgroundBudgetMs = Math.max(0.0, background);
    }

    private static double interpolate(double low, double high, double factor) {
        return low + ((high - low) * factor);
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    public double joinBudgetMs() { return joinBudgetMs; }
    public double postJoinBudgetMs() { return postJoinBudgetMs; }
    public double backgroundBudgetMs() { return backgroundBudgetMs; }
}
