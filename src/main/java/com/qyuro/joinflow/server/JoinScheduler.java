package com.qyuro.joinflow.server;

import com.qyuro.joinflow.QyuroJoinFlow;
import com.qyuro.joinflow.api.JoinFlowAPI;
import com.qyuro.joinflow.api.JoinPhase;
import com.qyuro.joinflow.config.ServerConfig;
import com.qyuro.joinflow.network.JoinFlowNetwork;
import com.qyuro.joinflow.network.JoinFlowNetworkScheduler;
import com.qyuro.joinflow.network.packets.Packets;
import com.qyuro.joinflow.task.JoinTask;
import com.qyuro.joinflow.task.JoinTaskProvider;
import com.qyuro.joinflow.task.TaskExecutionObserver;
import com.qyuro.joinflow.task.TaskPriority;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.UUID;

/** Lifecycle bridge and single-thread dispatcher for JoinFlow-owned post-login tasks. */
public final class JoinScheduler {
    private final JoinQueue sessions = new JoinQueue();
    private final PostJoinQueue postJoinQueue = new PostJoinQueue();
    private final JoinFlowNetworkScheduler network;
    private boolean queueFullWarningLogged;

    public JoinScheduler(JoinFlowNetworkScheduler network) {
        this.network = network;
    }

    public JoinSession onLogin(ServerPlayer player, long tick, LoadState loadState) {
        JoinSession existing = sessions.get(player.getUUID());
        if (existing != null) return existing;
        JoinSession session = sessions.start(player.getUUID(), System.nanoTime());
        if (session == null) {
            warnQueueFull();
            return null;
        }

        boolean channelPresent = ServerConfig.clientEnabled() && ServerConfig.networkEnabled()
                && JoinFlowNetwork.isPresent(player);
        session.setClientChannelPresent(channelPresent);

        if (ServerConfig.enabled()) {
            enqueueProviders(session, tick);
        }

        if (session.tasksPending() > 0 && channelPresent) {
            network.enqueue(session.playerId(), new Packets.HelloPacket(ServerConfig.heartbeatIntervalMs()),
                    TaskPriority.CRITICAL, 12, false, "hello");
        }
        if (session.tasksPending() == 0) {
            session.markComplete();
            sessions.complete(session.playerId());
            return session;
        }
        return session;
    }

    private void enqueueProviders(JoinSession session, long tick) {
        List<JoinTaskProvider> providers = new ArrayList<>(JoinFlowAPI.taskProviders());
        providers.sort(Comparator.comparing(JoinTaskProvider::qualifiedId));
        Map<String, JoinTaskProvider> byId = new HashMap<>();
        for (JoinTaskProvider provider : providers) byId.put(provider.qualifiedId(), provider);

        Set<String> skipped = new HashSet<>();
        for (JoinTaskProvider provider : providers) {
            if (!provider.safeToDefer() || provider.phase() != JoinPhase.POST_JOIN) {
                skipped.add(provider.qualifiedId());
                continue;
            }
            if (provider.dependencies().stream().anyMatch(dependency -> !byId.containsKey(dependency))) {
                QyuroJoinFlow.LOGGER.warn("[QyuroJoinFlow] Skipping provider {} because a declared dependency is not registered",
                        provider.qualifiedId());
                skipped.add(provider.qualifiedId());
            }
        }

        boolean changed;
        do {
            changed = false;
            for (JoinTaskProvider provider : providers) {
                if (skipped.contains(provider.qualifiedId())) continue;
                if (provider.dependencies().stream().anyMatch(skipped::contains)) {
                    QyuroJoinFlow.LOGGER.warn("[QyuroJoinFlow] Skipping provider {} because a declared dependency cannot be scheduled",
                            provider.qualifiedId());
                    skipped.add(provider.qualifiedId());
                    changed = true;
                }
            }
        } while (changed);

        Map<String, Integer> dependencyCounts = new HashMap<>();
        Map<String, List<String>> dependents = new HashMap<>();
        for (JoinTaskProvider provider : providers) {
            String id = provider.qualifiedId();
            if (skipped.contains(id)) continue;
            dependencyCounts.put(id, provider.dependencies().size());
            for (String dependency : provider.dependencies()) {
                dependents.computeIfAbsent(dependency, ignored -> new ArrayList<>()).add(id);
            }
        }

        PriorityQueue<String> ready = new PriorityQueue<>();
        dependencyCounts.forEach((id, count) -> { if (count == 0) ready.add(id); });
        List<JoinTaskProvider> ordered = new ArrayList<>(dependencyCounts.size());
        Set<String> orderedIds = new HashSet<>();
        while (!ready.isEmpty()) {
            String id = ready.remove();
            orderedIds.add(id);
            ordered.add(byId.get(id));
            for (String dependent : dependents.getOrDefault(id, List.of())) {
                Integer remaining = dependencyCounts.computeIfPresent(dependent, (ignored, count) -> count - 1);
                if (remaining != null && remaining == 0) ready.add(dependent);
            }
        }

        for (JoinTaskProvider provider : providers) {
            if (!skipped.contains(provider.qualifiedId()) && !orderedIds.contains(provider.qualifiedId())) {
                QyuroJoinFlow.LOGGER.warn("[QyuroJoinFlow] Skipping provider {} because its dependency graph contains a cycle",
                        provider.qualifiedId());
            }
        }

        for (JoinTaskProvider provider : ordered) {
            if (!postJoinQueue.enqueue(session, provider.qualifiedId(), provider.priority(),
                    provider.estimatedCostMicros(), provider.dependencies(), provider.task(), tick)) {
                warnQueueFull();
                break;
            }
        }
    }

    public JoinSession ensureSession(MinecraftServer server, UUID playerId, long tick) {
        JoinSession current = sessions.get(playerId);
        if (current != null) return current;
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (player == null || !ServerConfig.enabled()) return null;
        JoinSession created = sessions.start(playerId, System.nanoTime());
        if (created != null) {
            boolean channelPresent = ServerConfig.clientEnabled() && ServerConfig.networkEnabled()
                    && JoinFlowNetwork.isPresent(player);
            created.setClientChannelPresent(channelPresent);
            if (channelPresent) network.enqueue(playerId, new Packets.HelloPacket(ServerConfig.heartbeatIntervalMs()),
                    TaskPriority.CRITICAL, 12, false, "hello");
        }
        return created;
    }

    public boolean submit(JoinSession session, String taskId, TaskPriority priority,
                         long estimatedMicros, Set<String> dependencies, JoinTask task, long tick) {
        if (session == null || session.state() != JoinSession.State.ACTIVE || !ServerConfig.enabled()) return false;
        boolean accepted = postJoinQueue.enqueue(session, taskId, priority, estimatedMicros,
                dependencies, task, tick);
        if (!accepted) warnQueueFull();
        return accepted;
    }

    private void warnQueueFull() {
        if (!queueFullWarningLogged) {
            queueFullWarningLogged = true;
            QyuroJoinFlow.LOGGER.warn("[QyuroJoinFlow] JoinFlow task/session queue limit reached; optional work is being deferred or dropped (no player is kicked)");
        }
    }

    public void sendJoinState(JoinSession session, LoadState state) {
        if (session == null || !session.clientSupported() || !ServerConfig.sendServerState()) return;
        network.enqueue(session.playerId(), new Packets.JoinStatePacket(session.nextServerSequence(), session.phase(), state),
                TaskPriority.HIGH, 16, false, "join-state");
    }

    public void sendProgress(JoinSession session, LoadState state) {
        if (session == null || !session.clientSupported() || !ServerConfig.sendProgress()) return;
        int percent = (int) Math.round(session.progress() * 100.0);
        network.enqueue(session.playerId(), new Packets.JoinProgressPacket(session.nextServerSequence(), session.phase(),
                        percent, session.tasksCompleted(), session.tasksPending(), session.remainingEstimatedMicros()),
                TaskPriority.NORMAL, 48, false, "join-progress");
    }

    public int runTick(MinecraftServer server, long tick, LoadState state,
                       double joinBudgetMs, double postJoinBudgetMs, double backgroundBudgetMs,
                       TaskExecutionObserver observer) {
        int maxTasks = ServerConfig.allowParallelSafeTasks() ? ServerConfig.maxTasksPerTick() : 1;
        int considered = 0;

        considered += postJoinQueue.runRange(server, sessions, joinBudgetMs,
                TaskPriority.HIGH, TaskPriority.CRITICAL, tick, maxTasks - considered, observer);

        if (state != LoadState.EMERGENCY && considered < maxTasks) {
            considered += postJoinQueue.runRange(server, sessions, postJoinBudgetMs,
                    TaskPriority.NORMAL, TaskPriority.NORMAL, tick, maxTasks - considered, observer);
        }
        if ((state == LoadState.ECO || state == LoadState.NORMAL || state == LoadState.PROTECTED || state == LoadState.RECOVERY)
                && considered < maxTasks) {
            considered += postJoinQueue.runRange(server, sessions, backgroundBudgetMs,
                    TaskPriority.BACKGROUND, TaskPriority.LOW, tick, maxTasks - considered, observer);
        }
        return considered;
    }

    public JoinSession removeOnLogout(UUID playerId) {
        JoinSession session = sessions.remove(playerId);
        if (session != null) session.cancel();
        postJoinQueue.cancelPlayer(playerId, session);
        network.cancelPlayer(playerId);
        return session;
    }

    public JoinSession finish(UUID playerId) {
        JoinSession session = sessions.get(playerId);
        if (session == null) return null;
        session.markComplete();
        sessions.remove(playerId);
        return session;
    }

    public JoinSession timeout(UUID playerId) {
        JoinSession session = sessions.remove(playerId);
        if (session != null) {
            session.markTimedOut();
            postJoinQueue.cancelPlayer(playerId, session);
            network.cancelPlayer(playerId);
        }
        return session;
    }

    public int cancelAll() {
        int count = sessions.size();
        for (JoinSession session : sessions.sessions()) {
            session.cancel();
            postJoinQueue.cancelPlayer(session.playerId(), session);
            network.cancelPlayer(session.playerId());
        }
        sessions.cancelAll();
        postJoinQueue.cancelAll();
        network.clear();
        return count;
    }

    public JoinQueue sessions() { return sessions; }
    public PostJoinQueue postJoinQueue() { return postJoinQueue; }
    public int activeCount() { return sessions.size(); }
    public int queuedTaskCount() { return postJoinQueue.size(); }
    public int queuedSessionCount() { return sessions.queuedSessionCount(); }
    public long queuedEstimatedMicros() { return postJoinQueue.queuedEstimatedMicros(); }
    public int queuedNetworkPackets() { return network.size(); }
    public JoinFlowNetworkScheduler network() { return network; }
}
