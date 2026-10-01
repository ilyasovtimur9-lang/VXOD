package com.qyuro.joinflow.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.qyuro.joinflow.config.ServerConfig;
import com.qyuro.joinflow.learning.JoinLearningModel;
import com.qyuro.joinflow.profiler.JoinProfiler;
import com.qyuro.joinflow.server.JoinFlowRuntime;
import com.qyuro.joinflow.server.JoinSession;
import com.qyuro.joinflow.server.LoadState;
import com.qyuro.joinflow.task.TaskPriority;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.Comparator;
import java.util.Locale;

/** Administrative diagnostics. No command changes world state, player priority or connection status. */
public final class JoinFlowCommands {
    private JoinFlowCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("joinguard")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("status")
                        .executes(context -> status(context.getSource()))
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(context -> playerStatus(context.getSource(), EntityArgument.getPlayer(context, "player")))))
                .then(Commands.literal("joins").executes(context -> joins(context.getSource())))
                .then(Commands.literal("queue").executes(context -> queue(context.getSource())))
                .then(Commands.literal("hotspots").executes(context -> hotspots(context.getSource())))
                .then(Commands.literal("report").executes(context -> report(context.getSource())))
                .then(Commands.literal("reload").requires(source -> source.hasPermission(3))
                        .executes(context -> reload(context.getSource())))
                .then(Commands.literal("history")
                        .executes(context -> history(context.getSource()))
                        .then(Commands.literal("reset").requires(source -> source.hasPermission(3))
                                .executes(context -> resetHistory(context.getSource()))))
                .then(Commands.literal("adapters").executes(context -> adapters(context.getSource())))
                .then(Commands.literal("debug").requires(source -> source.hasPermission(3))
                        .executes(context -> debug(context.getSource())))
                .then(Commands.literal("test").requires(source -> source.hasPermission(3))
                        .then(Commands.literal("simulate_join_load").executes(context -> simulate(context.getSource())))
                        .then(Commands.literal("stress")
                                .then(Commands.argument("lanes", IntegerArgumentType.integer(1, 20))
                                        .executes(context -> stress(context.getSource(), IntegerArgumentType.getInteger(context, "lanes")))))
                        .then(Commands.literal("packet_batch").executes(context -> packetBatch(context.getSource())))));
    }

    private static int status(CommandSourceStack source) {
        JoinFlowRuntime runtime = runtime(source);
        if (runtime == null) return 0;
        var load = runtime.loadController();
        var budget = runtime.budgetController();
        source.sendSuccess(() -> Component.literal("§bQYURO JOINFLOW§r"), false);
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "Server: TPS %.2f | MSPT %.2f avg / %.2f peak | projected %.2f ms",
                load.estimatedTps(), load.averageMspt(), load.recentMaxMspt(), runtime.projectedMspt())), false);
        source.sendSuccess(() -> Component.literal("Load: " + runtime.loadState()
                + " | players " + source.getServer().getPlayerList().getPlayerCount()), false);
        source.sendSuccess(() -> Component.literal("Joins: active " + runtime.activeJoinSessions()
                + " | queued sessions " + runtime.queuedJoinSessions()
                + " | queued tasks " + runtime.scheduler().queuedTaskCount()), false);
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "Budget: join %.2f ms | post %.2f ms | background %.2f ms",
                budget.joinBudgetMs(), budget.postJoinBudgetMs(), budget.backgroundBudgetMs())), false);
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "JoinFlow channel: %d queued packets | %.1f KB sent | heap %.1f / %.1f MB",
                runtime.networkScheduler().size(), runtime.networkScheduler().totalBytesSent() / 1024.0,
                load.usedHeapBytes() / 1048576.0, load.maxHeapBytes() / 1048576.0)), false);
        return 1;
    }

    private static int playerStatus(CommandSourceStack source, ServerPlayer player) {
        JoinFlowRuntime runtime = runtime(source);
        if (runtime == null) return 0;
        JoinSession session = runtime.scheduler().sessions().get(player.getUUID());
        if (session == null) {
            source.sendFailure(Component.literal("No active JoinFlow post-login session for " + player.getGameProfile().getName()
                    + ". Vanilla authentication/player setup is not tracked by JoinFlow."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Player: " + player.getGameProfile().getName()
                + " | stage: " + session.phase() + " | state: " + session.state()), false);
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "JoinFlow session duration: %.2f s | progress: %d%% | estimated remaining: %.2f ms",
                session.durationMs(System.nanoTime()) / 1000.0, Math.round(session.progress() * 100.0),
                session.remainingEstimatedMicros() / 1000.0)), false);
        source.sendSuccess(() -> Component.literal("Tasks: completed " + session.tasksCompleted() + " / pending "
                + session.tasksPending() + " / failed " + session.tasksFailed()), false);
        source.sendSuccess(() -> Component.literal("Own-channel bytes: sent " + session.bytesSent()
                + " / received " + session.bytesReceived() + " | client ready " + session.clientReady()), false);
        return 1;
    }

    private static int joins(CommandSourceStack source) {
        JoinFlowRuntime runtime = runtime(source);
        if (runtime == null) return 0;
        var sessions = runtime.scheduler().sessions().sessions();
        source.sendSuccess(() -> Component.literal("Active JoinFlow sessions: " + sessions.size()), false);
        for (JoinSession session : sessions) {
            ServerPlayer player = source.getServer().getPlayerList().getPlayer(session.playerId());
            String name = player == null ? session.playerId().toString() : player.getGameProfile().getName();
            source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                    "%s | %s | %.1f%% | tasks %d pending / %d completed",
                    name, session.phase(), session.progress() * 100.0, session.tasksPending(), session.tasksCompleted())), false);
        }
        return 1;
    }

    private static int queue(CommandSourceStack source) {
        JoinFlowRuntime runtime = runtime(source);
        if (runtime == null) return 0;
        source.sendSuccess(() -> Component.literal("JoinFlow queue: sessions=" + runtime.activeJoinSessions()
                + " | pending tasks=" + runtime.scheduler().queuedTaskCount()
                + " | estimated queued work=" + String.format(Locale.ROOT, "%.2f ms", runtime.queuedTaskEstimateMicros() / 1000.0)
                + " | own-channel packets=" + runtime.networkScheduler().size()), false);
        source.sendSuccess(() -> Component.literal("No Forge login/vanilla packet queue is intercepted; only opt-in JoinFlow tasks are scheduled."), false);
        return 1;
    }

    private static int hotspots(CommandSourceStack source) {
        JoinFlowRuntime runtime = runtime(source);
        if (runtime == null) return 0;
        var summaries = runtime.profiler().learningModel().snapshot().values().stream()
                .filter(summary -> summary.sampleCount() > 0)
                .sorted(Comparator.comparingLong(JoinLearningModel.TaskSummary::p95MicrosApprox).reversed())
                .limit(10).toList();
        source.sendSuccess(() -> Component.literal("JOIN HOTSPOTS — MEASURED JoinFlow-owned tasks only"), false);
        if (summaries.isEmpty()) {
            source.sendSuccess(() -> Component.literal("No JoinFlow task samples yet. External mod phases are OBSERVED only when directly measured by that integration."), false);
            return 1;
        }
        for (int i = 0; i < summaries.size(); i++) {
            var summary = summaries.get(i);
            source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                    "%d. %s | avg %.3f ms | p95~ %.3f ms | max %.3f ms | samples %d",
                    summaries.indexOf(summary) + 1, summary.taskType(), summary.averageMicros() / 1000.0,
                    summary.p95MicrosApprox() / 1000.0, summary.maxMicros() / 1000.0, summary.sampleCount())), false);
        }
        return 1;
    }

    private static int report(CommandSourceStack source) {
        JoinFlowRuntime runtime = runtime(source);
        if (runtime == null) return 0;
        JoinProfiler profiler = runtime.profiler();
        source.sendSuccess(() -> Component.literal("QYURO JOINFLOW REPORT — observed, not causal attribution"), false);
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "Joins recorded: %d | average post-login JoinFlow session: %.2f ms | p95 recent: %.2f ms | max: %.2f ms",
                profiler.totalJoins(), profiler.averageJoinMs(), profiler.p95JoinMs(), profiler.maximumDurationMs())), false);
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "Current load: %s | MSPT avg %.2f / recent max %.2f | spikes %d | emergency count %d",
                runtime.loadState(), runtime.loadController().averageMspt(), runtime.loadController().recentMaxMspt(),
                runtime.spikeGuard().totalSpikes(), profiler.emergencyCount())), false);
        source.sendSuccess(() -> Component.literal("Profile contains JoinFlow-owned task timings only; third-party mod causality is not inferred."), false);
        return 1;
    }

    private static int reload(CommandSourceStack source) {
        JoinFlowRuntime runtime = runtime(source);
        if (runtime == null) return 0;
        runtime.reloadRuntimeSettings();
        source.sendSuccess(() -> Component.literal("JoinFlow runtime settings reapplied. Forge watches the registered TOML config and applies live-safe values; client protocol/UI changes may require reconnect or restart."), false);
        return 1;
    }

    private static int history(CommandSourceStack source) {
        JoinFlowRuntime runtime = runtime(source);
        if (runtime == null) return 0;
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "History: %d joins retained | avg %.2f ms | p95 %.2f ms | max %.2f ms",
                runtime.profiler().recentJoins().size(), runtime.profiler().averageJoinMs(),
                runtime.profiler().p95JoinMs(), runtime.profiler().maximumDurationMs())), false);
        return 1;
    }

    private static int resetHistory(CommandSourceStack source) {
        JoinFlowRuntime runtime = runtime(source);
        if (runtime == null) return 0;
        runtime.resetHistory();
        source.sendSuccess(() -> Component.literal("JoinFlow history reset. Active sessions were left untouched."), false);
        return 1;
    }

    private static int adapters(CommandSourceStack source) {
        JoinFlowRuntime runtime = runtime(source);
        if (runtime == null) return 0;
        source.sendSuccess(() -> Component.literal("Optional compatibility — detection is OBSERVATIONAL; no third-party code is intercepted."), false);
        for (var adapter : runtime.compatibilityManager().snapshot().values()) {
            source.sendSuccess(() -> Component.literal(adapter.displayName() + " [" + adapter.modId() + "]: "
                    + (adapter.loaded() ? "loaded" : "not loaded") + " | " + adapter.mode()), false);
        }
        return 1;
    }

    private static int debug(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal("JoinFlow debug config: " + ServerConfig.debug()
                + " | profiling mode: " + ServerConfig.profilingMode()
                + " | current load: " + currentState(source)), false);
        return 1;
    }

    private static int simulate(CommandSourceStack source) {
        JoinFlowRuntime runtime = runtime(source);
        if (runtime == null) return 0;
        int accepted = runtime.queueSyntheticTasks(1, 64, TaskPriority.NORMAL);
        source.sendSuccess(() -> Component.literal("Queued " + accepted
                + " bounded CPU-only synthetic tasks. No players, chunks, packets or world state were changed."), false);
        return 1;
    }

    private static int stress(CommandSourceStack source, int lanes) {
        JoinFlowRuntime runtime = runtime(source);
        if (runtime == null) return 0;
        int accepted = 0;
        accepted += runtime.queueSyntheticTasks(lanes, 50, TaskPriority.BACKGROUND);
        accepted += runtime.queueSyntheticTasks(lanes, 25, TaskPriority.LOW);
        accepted += runtime.queueSyntheticTasks(lanes, 15, TaskPriority.NORMAL);
        accepted += runtime.queueSyntheticTasks(lanes, 8, TaskPriority.HIGH);
        accepted += runtime.queueSyntheticTasks(lanes, 2, TaskPriority.CRITICAL);
        int count = accepted;
        source.sendSuccess(() -> Component.literal("Queued " + count + " synthetic tasks across " + lanes
                + " fair-share lanes. Tasks are bounded and execute under the normal adaptive budget."), false);
        return 1;
    }

    private static int packetBatch(CommandSourceStack source) {
        int bytes = 65536;
        int parts = JoinFlowRuntime.packetCountFor(bytes);
        source.sendSuccess(() -> Component.literal("Packet split simulation: " + bytes + " bytes -> " + parts
                + " JoinFlow batches at max_packet_bytes=" + ServerConfig.maxPacketBytes()
                + ". Nothing was sent to a player."), false);
        return 1;
    }

    private static JoinFlowRuntime runtime(CommandSourceStack source) {
        JoinFlowRuntime runtime = JoinFlowRuntime.current();
        if (runtime == null) source.sendFailure(Component.literal("JoinFlow server runtime is not active."));
        return runtime;
    }

    private static String currentState(CommandSourceStack source) {
        JoinFlowRuntime runtime = JoinFlowRuntime.current();
        return runtime == null ? LoadState.NORMAL.name() : runtime.loadState().name();
    }
}
