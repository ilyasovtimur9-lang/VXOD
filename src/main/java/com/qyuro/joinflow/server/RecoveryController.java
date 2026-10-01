package com.qyuro.joinflow.server;

/** Hysteretic state recovery. A severe event restores budget in discrete, delayed steps. */
public final class RecoveryController {
    private boolean recovering;
    private int stableTicks;
    private double recoveryFactor = 1.0;
    private LoadState lastRawState = LoadState.ECO;

    public LoadState update(LoadState rawState, int recoveryTicks) {
        if (rawState == LoadState.RECOVERY) rawState = LoadState.NORMAL;

        if (rawState.severity() >= LoadState.CRITICAL.severity()) {
            recovering = true;
            stableTicks = 0;
            recoveryFactor = 0.0;
            lastRawState = rawState;
            return rawState;
        }

        if (rawState == LoadState.PROTECTED) {
            if (!recovering) recoveryFactor = 0.50;
            recovering = true;
            stableTicks = 0;
            recoveryFactor = Math.min(recoveryFactor, 0.50);
            lastRawState = rawState;
            return rawState;
        }

        if (!recovering) {
            lastRawState = rawState;
            return rawState;
        }

        if (rawState.severity() >= LoadState.PROTECTED.severity()) {
            stableTicks = 0;
            recoveryFactor = Math.min(recoveryFactor, 0.25);
            lastRawState = rawState;
            return rawState;
        }

        stableTicks++;
        int step = Math.max(1, recoveryTicks);
        if (stableTicks >= step) {
            stableTicks = 0;
            recoveryFactor = Math.min(1.0, recoveryFactor + 0.20);
        }
        lastRawState = rawState;
        if (recoveryFactor >= 1.0) {
            recovering = false;
            return rawState;
        }
        return LoadState.RECOVERY;
    }

    public double recoveryFactor() { return recoveryFactor; }
    public boolean recovering() { return recovering; }
    public LoadState lastRawState() { return lastRawState; }
}
