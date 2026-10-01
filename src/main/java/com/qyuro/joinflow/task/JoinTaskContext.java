package com.qyuro.joinflow.task;

import com.qyuro.joinflow.api.JoinPhase;
import net.minecraft.server.level.ServerPlayer;

import java.util.Optional;
import java.util.UUID;

/** Per-invocation context; the player reference is valid only during this server-thread call. */
public final class JoinTaskContext {
    private final UUID playerId;
    private final ServerPlayer player;
    private final JoinPhase phase;
    private final CancellationToken cancellationToken;
    private final boolean synthetic;

    public JoinTaskContext(UUID playerId, ServerPlayer player, JoinPhase phase,
                           CancellationToken cancellationToken, boolean synthetic) {
        this.playerId = playerId;
        this.player = player;
        this.phase = phase;
        this.cancellationToken = cancellationToken;
        this.synthetic = synthetic;
    }

    public UUID playerId() { return playerId; }
    public Optional<ServerPlayer> player() { return Optional.ofNullable(player); }
    public JoinPhase phase() { return phase; }
    public CancellationToken cancellationToken() { return cancellationToken; }
    public boolean synthetic() { return synthetic; }
    public boolean isCancelled() { return cancellationToken.isCancelled(); }
}
