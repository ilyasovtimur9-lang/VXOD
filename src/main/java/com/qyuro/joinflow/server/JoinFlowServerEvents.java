package com.qyuro.joinflow.server;

import com.qyuro.joinflow.QyuroJoinFlow;
import com.qyuro.joinflow.command.JoinFlowCommands;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/** Forge lifecycle hooks only. Vanilla login/auth/registry/chunk code is not intercepted. */
public final class JoinFlowServerEvents {
    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        try {
            JoinFlowRuntime.start(event.getServer());
        } catch (Throwable throwable) {
            rethrowFatal(throwable);
            QyuroJoinFlow.LOGGER.error("[QyuroJoinFlow] ERROR component=server-start exception=JoinFlow startup failed; vanilla server continues", throwable);
        }
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        try {
            JoinFlowRuntime.stop();
        } catch (Throwable throwable) {
            rethrowFatal(throwable);
            QyuroJoinFlow.LOGGER.error("[QyuroJoinFlow] ERROR component=server-stop exception=JoinFlow shutdown failed", throwable);
        }
    }

    @SubscribeEvent
    public void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        JoinFlowRuntime runtime = JoinFlowRuntime.current();
        if (runtime == null) return;
        try {
            runtime.onPlayerLogin(player);
        } catch (Throwable throwable) {
            rethrowFatal(throwable);
            QyuroJoinFlow.LOGGER.error("[QyuroJoinFlow] ERROR component=login-event player={} phase=POST_JOIN exception=observer failed; normal Forge login remains intact",
                    player.getUUID(), throwable);
        }
    }

    @SubscribeEvent
    public void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        JoinFlowRuntime runtime = JoinFlowRuntime.current();
        if (runtime != null) runtime.onPlayerLogout(player.getUUID());
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        JoinFlowRuntime runtime = JoinFlowRuntime.current();
        if (runtime == null) return;
        try {
            if (event.phase == TickEvent.Phase.START) runtime.onTickStart(event.getServer());
            else runtime.onTickEnd(event.getServer());
        } catch (Throwable throwable) {
            rethrowFatal(throwable);
            QyuroJoinFlow.LOGGER.error("[QyuroJoinFlow] ERROR component=tick phase={} exception=observer failure; continuing normal server tick",
                    event.phase, throwable);
        }
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        JoinFlowCommands.register(event.getDispatcher());
    }

    private static void rethrowFatal(Throwable throwable) {
        if (throwable instanceof VirtualMachineError error) throw error;
        if (throwable instanceof ThreadDeath death) throw death;
    }
}
