package com.qyuro.joinflow.server;

import com.qyuro.joinflow.QyuroJoinFlow;
import com.qyuro.joinflow.api.JoinFlowAPI;
import com.qyuro.joinflow.api.JoinPhase;
import com.qyuro.joinflow.compatibility.CompatibilityManager;
import com.qyuro.joinflow.config.ServerConfig;
import com.qyuro.joinflow.learning.CostEstimator;
import com.qyuro.joinflow.network.DeltaSync;
import com.qyuro.joinflow.network.JoinFlowNetworkScheduler;
import com.qyuro.joinflow.network.SyncDataRegistry;
import com.qyuro.joinflow.network.packets.Packets;
import com.qyuro.joinflow.profiler.JoinProfiler;
import com.qyuro.joinflow.profiler.TaskTiming;
import com.qyuro.joinflow.storage.HistoryStore;
import com.qyuro.joinflow.storage.JoinFlowIO;
import com.qyuro.joinflow.task.JoinTask;
import com.qyuro.joinflow.task.TaskExecutionObserver;
import com.qyuro.joinflow.task.TaskPriority;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/** Server runtime. It retains UUID-only sessions and never stores Player/Level references. */
public final class JoinFlowRuntime implements TaskExecutionObserver {
    private static final int MAX_SLOW_WARNING_KEYS = 256;
    private static final String OTHER_SLOW_WARNING_KEY = "other";
    private static volatile JoinFlowRuntime current;
    private static final AtomicInteger NEXT_BATCH_ID = new AtomicInteger(1);
    private static volatile long syntheticSink;

    private final GlobalLoadController loadController = new GlobalLoadController();
    private final SpikeGuard spikeGuard = new SpikeGuard();
    private final RecoveryController recoveryController = new RecoveryController();
    private final AdaptiveBudgetController budgetController = new AdaptiveBudgetController();
    private final ExistingPlayerProtection playerProtection = new ExistingPlayerProtection();
    private final JoinLoadEstimator loadEstimator = new JoinLoadEstimator();
    private final CostEstimator costEstimator = new CostEstimator();
    private final JoinProfiler profiler = new JoinProfiler();
    private final JoinFlowNetworkScheduler networkScheduler = new JoinFlowNetworkScheduler();
    private final JoinScheduler scheduler = new JoinScheduler(networkScheduler);
    private final CompatibilityManager compatibilityManager = new CompatibilityManager();
    private final JoinFlowIO io = new JoinFlowIO();
    private final HistoryStore historyStore = new HistoryStore(io);
    private final Map<String, Long> nextSlowWarningNanos = new HashMap<>();

    private LoadState loadState = LoadState.NORMAL;
    private LoadState lastBroadcastState = LoadState.NORMAL;
    private double projectedMspt;
    private long previousProjectionTick;
    private long tickCounter;
    private long lastProgressTick = Long.MIN_VALUE;
    private boolean disabledQueueCleared;
    private boolean historyLoaded;
    private boolean historyReset;
    private boolean historyWritePending;
    private boolean stopped;

    private JoinFlowRuntime() {
    }

    public static JoinFlowRuntime current() { return current; }

    public static synchronized void start(MinecraftServer server) {
        if (current != null) current.shutdown();
        JoinFlowRuntime runtime = new JoinFlowRuntime();
        current = runtime;
        runtime.compatibilityManager.detectLoadedMods();
        runtime.historyStore.loadAsync(server, runtime::onHistoryLoaded);
        QyuroJoinFlow.LOGGER.info("[QyuroJoinFlow] Started");
        QyuroJoinFlow.LOGGER.info("[QyuroJoinFlow] Adaptive scheduler: {}", ServerConfig.adaptiveEnabled() ? "enabled" : "fixed budgets");
        QyuroJoinFlow.LOGGER.info("[QyuroJoinFlow] Client protocol: optional");
        QyuroJoinFlow.LOGGER.info("[QyuroJoinFlow] Profiling: {}", ServerConfig.profilingMode());
    }

    public static synchronized void stop() {
        JoinFlowRuntime runtime = current;
        current = null;
        if (runtime != null) runtime.shutdown();
    }

    private void shutdown() {
        if (stopped) return;
        stopped = true;
        scheduler.cancelAll();
        if (historyLoaded) historyStore.saveHistory(profiler.historySnapshot());
        io.close();
    }

    private void onHistoryLoaded(java.util.List<JoinProfiler.JoinRecord> records) {
        if (current != this || stopped) return;
        if (!historyReset) profiler.restore(records);
        historyLoaded = true;
        if (historyWritePending) {
            historyWritePending = false;
            historyStore.saveHistory(profiler.historySnapshot());
        }
    }

    private void persistHistory() {
        if (!historyLoaded) {
            historyWritePending = true;
            return;
        }
        historyStore.saveHistory(profiler.historySnapshot());
    }

    public void onTickStart(MinecraftServer server) {
        if (stopped) return;
        loadController.onTickStart(System.nanoTime());
        tickCounter = loadController.tickCount();

        LoadState measured = loadController.classify();
        LoadState spike = spikeGuard.observe(loadController.lastMspt());
        long now = System.nanoTime();
        if (now - previousProjectionTick >= 50_000_000L) {
            double avgCost = profiler.learningModel().averageMicros();
            projectedMspt = loadEstimator.predictMspt(loadController.averageMspt(), scheduler.activeCount(),
                    scheduler.queuedEstimatedMicros(), avgCost, ServerConfig.maxConcurrentJoins());
            previousProjectionTick = now;
        }
        LoadState predicted = loadEstimator.projectedState(projectedMspt);
        LoadState target = LoadState.moreSevere(LoadState.moreSevere(measured, spike), predicted);
        loadState = recoveryController.update(target, ServerConfig.recoveryTicks());
        budgetController.update(loadState, recoveryController.recoveryFactor());

        if (ServerConfig.debug() && tickCounter % 200 == 0) {
            QyuroJoinFlow.LOGGER.debug("[QyuroJoinFlow] load={} mspt(avg={}/max={}) projected={} joins={} tasks={}",
                    loadState, format(loadController.averageMspt()), format(loadController.recentMaxMspt()),
                    format(projectedMspt), scheduler.activeCount(), scheduler.queuedTaskCount());
        }
    }

    public void onTickEnd(MinecraftServer server) {
        if (stopped) return;
        if (!ServerConfig.enabled()) {
            if (!disabledQueueCleared) {
                var sessions = scheduler.sessions().sessions();
                int cancelled = scheduler.cancelAll();
                for (JoinSession session : sessions) {
                    session.setPhase(JoinPhase.COMPLETE);
                    scheduler.sendJoinState(session, loadState);
                    scheduler.sendProgress(session, loadState);
                }
                if (cancelled > 0) QyuroJoinFlow.LOGGER.info("[QyuroJoinFlow] Disabled; cancelled {} optional JoinFlow sessions", cancelled);
                disabledQueueCleared = true;
            }
        } else {
            disabledQueueCleared = false;
            runManagedWork(server);
            sendLoadStateChanges();
            if (tickCounter % 5 == 0 && tickCounter != lastProgressTick) {
                sendProgressUpdates();
                lastProgressTick = tickCounter;
            }
            if (tickCounter % 20 == 0) expireSessions();
        }

        if (!ServerConfig.networkEnabled() || !ServerConfig.clientEnabled()) {
            networkScheduler.clear();
            return;
        }
        try {
            networkScheduler.tick(server, scheduler.sessions(), loadState);
        } catch (Throwable throwable) {
            rethrowFatal(throwable);
            QyuroJoinFlow.LOGGER.error("[QyuroJoinFlow] ERROR component=network-scheduler exception=outbound scheduler failure; dropping optional queue", throwable);
            networkScheduler.clear();
        }
    }

    private void runManagedWork(MinecraftServer server) {
        int onlinePlayers = server.getPlayerList().getPlayerCount();
        double joinMultiplier = playerProtection.joinMultiplier(loadState, onlinePlayers, scheduler.activeCount());
        double postMultiplier = playerProtection.postJoinMultiplier(loadState);
        double joinBudget = budgetController.joinBudgetMs() * joinMultiplier;
        double postBudget = budgetController.postJoinBudgetMs() * postMultiplier;
        double backgroundBudget = budgetController.backgroundBudgetMs() * postMultiplier;
        try {
            scheduler.runTick(server, tickCounter, loadState, joinBudget, postBudget, backgroundBudget, this);
        } catch (Throwable throwable) {
            rethrowFatal(throwable);
            QyuroJoinFlow.LOGGER.error("[QyuroJoinFlow] ERROR component=task-scheduler exception=JoinFlow-owned scheduler failure; work will be deferred", throwable);
        }
    }

    private void sendLoadStateChanges() {
        if (lastBroadcastState == loadState) return;
        for (JoinSession session : scheduler.sessions().sessions()) scheduler.sendJoinState(session, loadState);
        lastBroadcastState = loadState;
    }

    private void sendProgressUpdates() {
        for (JoinSession session : scheduler.sessions().sessions()) {
            scheduler.sendProgress(session, loadState);
        }
    }

    private void expireSessions() {
        long now = System.nanoTime();
        for (JoinSession session : scheduler.sessions().sessions()) {
            if (session.durationMs(now) <= ServerConfig.maxSessionSeconds() * 1000.0) continue;
            QyuroJoinFlow.LOGGER.warn("[QyuroJoinFlow] JoinFlow session exceeded {} seconds; cancelling only its safe optional work (player={})",
                    ServerConfig.maxSessionSeconds(), session.playerId());
            JoinSession timedOut = scheduler.timeout(session.playerId());
            if (timedOut != null) {
                timedOut.setPhase(JoinPhase.COMPLETE);
                scheduler.sendJoinState(timedOut, loadState);
                scheduler.sendProgress(timedOut, loadState);
                recordCompleted(timedOut, "TIMEOUT");
            }
        }
    }

    public void onPlayerLogin(ServerPlayer player) {
        if (stopped) return;
        JoinSession session;
        try {
            session = scheduler.onLogin(player, tickCounter, loadState);
        } catch (Throwable throwable) {
            rethrowFatal(throwable);
            QyuroJoinFlow.LOGGER.error("[QyuroJoinFlow] ERROR component=login-session player={} phase=POST_JOIN exception=failed to create optional JoinFlow session",
                    player.getUUID(), throwable);
            return;
        }
        if (session != null && session.state() == JoinSession.State.COMPLETE) recordCompleted(session, loadState.name());
    }

    public void onPlayerLogout(UUID playerId) {
        if (stopped) return;
        JoinSession session = scheduler.removeOnLogout(playerId);
        if (session != null && session.tasksCompleted() > 0) {
            recordCompleted(session, loadState.name());
        }
    }

    private void recordCompleted(JoinSession session, String state) {
        JoinProfiler.JoinRecord record = new JoinProfiler.JoinRecord(System.currentTimeMillis(),
                session.durationMs(System.nanoTime()), loadController.recentMaxMspt(), session.tasksCompleted(),
                session.tasksFailed(), session.bytesSent(), session.bytesReceived(), state);
        profiler.recordJoin(record);
        persistHistory();
        if (ServerConfig.writeReports() && profiler.totalJoins() % ServerConfig.writeFullReportEvery() == 0L) {
            historyStore.writeReport(record);
        }
    }

    @Override
    public void onTaskComplete(JoinSession session, TaskTiming timing, boolean success, Throwable failure) {
        profiler.recordTask(timing);
        if (session != null) {
            session.taskFinished(timing.taskId(), timing.estimatedMicros(),
                    Math.max(0L, (timing.endedNanos() - timing.startedNanos()) / 1000L), success);
        }
        warnSlowTask(timing);

        if (!success) {
            String player = session == null ? "synthetic" : session.playerId().toString();
            QyuroJoinFlow.LOGGER.error("[QyuroJoinFlow] ERROR component=task player={} phase=POST_JOIN task={} exception=JoinFlow adapter task failed",
                    player, timing.taskId(), failure);
            if (ServerConfig.failsafeEnabled() && ServerConfig.disableFailedOptionalAdapter()) {
                JoinFlowAPI.disableProviderByQualifiedId(timing.taskId());
            }
        }

        if (session != null && session.tasksPending() == 0 && session.pendingSyncsView().isEmpty()) {
            completeSession(session);
        }
    }

    @Override
    public void onTaskDiscarded(JoinSession session, TaskExecutionObserver.ScheduledTaskView task, String reason) {
        if (ServerConfig.debug()) {
            QyuroJoinFlow.LOGGER.debug("[QyuroJoinFlow] Deferred task discarded: {} reason={}", task.taskId(), reason);
        }
        if (session != null && session.tasksPending() == 0 && session.pendingSyncsView().isEmpty()) {
            completeSession(session);
        }
    }

    private void warnSlowTask(TaskTiming timing) {
        if (ServerConfig.profilingMode().equals("OFF")) return;
        double durationMs = timing.durationMs();
        if (durationMs < ServerConfig.slowTaskMs()) return;
        long now = System.nanoTime();
        String warningKey = timing.taskId();
        if (!nextSlowWarningNanos.containsKey(warningKey)
                && nextSlowWarningNanos.size() >= MAX_SLOW_WARNING_KEYS - 1) {
            warningKey = OTHER_SLOW_WARNING_KEY;
        }
        long next = nextSlowWarningNanos.getOrDefault(warningKey, 0L);
        if (now < next) return;
        nextSlowWarningNanos.put(warningKey, now + 60_000_000_000L);
        var summary = profiler.learningModel().summary(timing.taskId());
        String severity = durationMs >= ServerConfig.criticalTaskMs() ? "CRITICAL"
                : durationMs >= ServerConfig.verySlowTaskMs() ? "MAJOR" : "SLOW";
        QyuroJoinFlow.LOGGER.warn("[QyuroJoinFlow] {} MEASURED JoinFlow task={} last={}ms count={} avg={}ms p95~={}ms max={}ms queueWait={}ms",
                severity, timing.taskId(), format(durationMs), summary.sampleCount(),
                format(summary.averageMicros() / 1000.0), format(summary.p95MicrosApprox() / 1000.0),
                format(summary.maxMicros() / 1000.0), format(timing.queueWaitMs()));
    }

    private void completeSession(JoinSession session) {
        if (session.state() != JoinSession.State.ACTIVE) return;
        session.setPhase(JoinPhase.COMPLETE);
        scheduler.sendJoinState(session, loadState);
        scheduler.sendProgress(session, loadState);
        JoinSession completed = scheduler.finish(session.playerId());
        if (completed != null) recordCompleted(completed, loadState.name());
    }

    public void onHelloAck(ServerPlayer player) {
        JoinSession session = scheduler.sessions().get(player.getUUID());
        if (session == null || !session.clientChannelPresent() || !ServerConfig.clientEnabled()
                || session.clientSupported()) return;
        session.setClientSupported(true);
        session.recordBytesReceived(8L);
        scheduler.sendJoinState(session, loadState);
        scheduler.sendProgress(session, loadState);
    }

    public void onClientReady(ServerPlayer player, int sequence, boolean ready) {
        JoinSession session = validClientPacket(player, sequence, 8L);
        if (session == null || !session.clientSupported()) return;
        session.setClientReady(ready);
        scheduler.sendJoinState(session, loadState);
    }

    public void onClientStatus(ServerPlayer player, int sequence, int queueDepth, long processingMicros) {
        JoinSession session = validClientPacket(player, sequence, 16L);
        if (session == null || !session.clientSupported()) return;
        session.setClientStatus(queueDepth, processingMicros);
    }

    public void onHeartbeat(ServerPlayer player, int sequence) {
        JoinSession session = validClientPacket(player, sequence, 8L);
        if (session != null && session.clientSupported()) session.heartbeat(System.nanoTime());
    }

    public void onSyncRequest(ServerPlayer player, int sequence, String key, int knownVersion, String knownHash) {
        JoinSession session = validClientPacket(player, sequence, 180L);
        if (session == null || !session.clientSupported() || !session.clientReady()) return;
        SyncDataRegistry.StaticData data = SyncDataRegistry.get(key);
        if (data == null) return;
        // Client-provided hashes are advisory only. Ask the client cache for a separate result before suppressing data.
        queueCacheCheck(session, data);
    }

    public void onCacheResult(ServerPlayer player, int sequence, String key, int version, String hash, boolean matches) {
        JoinSession session = validClientPacket(player, sequence, 180L);
        if (session == null || !session.clientSupported()) return;
        JoinSession.PendingSync pending = session.pendingSync(key);
        if (pending == null || pending.version() != version || !pending.hash().equals(hash)) return;
        SyncDataRegistry.StaticData data = SyncDataRegistry.get(key);
        if (data == null || data.version() != version || !data.hash().equals(hash)) {
            session.clearPendingSync(key);
            return;
        }
        if (matches && ServerConfig.deltaSyncEnabled() && DeltaSync.matches(version, hash, data)) {
            session.clearPendingSync(key);
            finishIfIdle(session);
            return;
        }
        int expected = packetCountFor(data.sizeBytes());
        int queued = networkScheduler.enqueueStaticData(session.playerId(), data, nextBatchId());
        if (queued != expected) {
            session.clearPendingSync(key);
            QyuroJoinFlow.LOGGER.warn("[QyuroJoinFlow] Could not queue full sync batch key={} player={}; optional data was dropped without affecting gameplay",
                    key, session.playerId());
        }
    }

    public void onSyncAck(ServerPlayer player, int sequence, String key, int version, String hash, boolean applied) {
        JoinSession session = validClientPacket(player, sequence, 180L);
        if (session == null || !session.clientSupported()) return;
        JoinSession.PendingSync pending = session.pendingSync(key);
        if (pending == null || pending.version() != version || !pending.hash().equals(hash)) return;
        session.clearPendingSync(key);
        if (applied) session.recordBytesReceived(180L);
        else QyuroJoinFlow.LOGGER.warn("[QyuroJoinFlow] Client did not apply optional sync key={} player={}", key, session.playerId());
        finishIfIdle(session);
    }

    private JoinSession validClientPacket(ServerPlayer player, int sequence, long bytes) {
        JoinSession session = scheduler.sessions().get(player.getUUID());
        if (session == null || !session.clientChannelPresent() || !ServerConfig.networkEnabled()
                || !ServerConfig.clientEnabled()) return null;
        long now = System.nanoTime();
        if (!session.acceptTelemetry(now, ServerConfig.maxTelemetryPacketsPerSecond())) return null;
        if (!session.acceptClientSequence(sequence)) return null;
        session.recordBytesReceived(bytes);
        return session;
    }

    private void queueCacheCheck(JoinSession session, SyncDataRegistry.StaticData data) {
        session.addPendingSync(data.key(), data.version(), data.hash());
        Packets.CacheInfoPacket packet = new Packets.CacheInfoPacket(data.key(), data.version(), data.hash(), data.cacheable());
        int bytes = 100 + data.key().length();
        if (!networkScheduler.enqueue(session.playerId(), packet, TaskPriority.HIGH, bytes, true,
                "cache-info:" + data.key())) {
            session.clearPendingSync(data.key());
        }
    }

    public static void requestStaticSync(UUID playerId, String key) {
        JoinFlowRuntime runtime = current;
        if (runtime == null) return;
        JoinSession session = runtime.scheduler.sessions().get(playerId);
        SyncDataRegistry.StaticData data = SyncDataRegistry.get(key);
        if (session == null || !session.clientSupported() || !session.clientReady() || data == null) return;
        runtime.queueCacheCheck(session, data);
    }

    public static void submitPostJoinTask(MinecraftServer server, UUID playerId, String taskId,
                                          TaskPriority priority, long estimatedMicros,
                                          Set<String> dependencies, JoinTask task) {
        JoinFlowRuntime runtime = current;
        if (runtime == null || !ServerConfig.enabled()) return;
        JoinSession session = runtime.scheduler.ensureSession(server, playerId, runtime.tickCounter);
        if (session == null) return;
        long estimate = runtime.costEstimator.estimateMicros(runtime.profiler.learningModel(), taskId, estimatedMicros);
        if (!runtime.scheduler.submit(session, taskId, priority, estimate, dependencies, task, runtime.tickCounter)) {
            if (session.tasksPending() == 0) runtime.completeSession(session);
        } else if (session.clientChannelPresent() && !session.clientSupported()) {
            // A Hello was queued when the API-only session was created; no client feature is required.
        }
    }

    private void finishIfIdle(JoinSession session) {
        if (session.tasksPending() == 0 && session.pendingSyncsView().isEmpty()) completeSession(session);
    }

    public int queueSyntheticTasks(int lanes, int tasksPerLane, TaskPriority priority) {
        int accepted = 0;
        int boundedLanes = Math.max(1, Math.min(20, lanes));
        int boundedTasks = Math.max(1, Math.min(1000, tasksPerLane));
        for (int lane = 0; lane < boundedLanes; lane++) {
            UUID laneId = UUID.nameUUIDFromBytes(("qyuro_joinflow:test:" + lane).getBytes(StandardCharsets.UTF_8));
            for (int index = 0; index < boundedTasks; index++) {
                String id = "test:synthetic-" + lane + "-" + index;
                boolean added = scheduler.postJoinQueue().enqueueSynthetic(laneId, id, priority, 100L,
                        context -> {
                            long value = context.playerId().getMostSignificantBits() ^ indexSeed(context.playerId());
                            for (int step = 0; step < 256; step++) value = (value * 0x9E3779B97F4A7C15L) ^ step;
                            syntheticSink = value;
                        }, tickCounter);
                if (added) accepted++;
            }
        }
        return accepted;
    }

    private static long indexSeed(UUID lane) {
        return lane.getLeastSignificantBits();
    }

    public static int packetCountFor(int payloadBytes) {
        int payloadLimit = Math.max(1, ServerConfig.maxPacketBytes() - 320);
        return Math.max(1, (Math.max(0, payloadBytes) + payloadLimit - 1) / payloadLimit);
    }

    public void resetHistory() {
        historyReset = true;
        profiler.clearHistory();
        persistHistory();
    }

    public void reloadRuntimeSettings() {
        budgetController.update(loadState, recoveryController.recoveryFactor());
        disabledQueueCleared = false;
    }

    public LoadState loadState() { return loadState; }
    public double projectedMspt() { return projectedMspt; }
    public GlobalLoadController loadController() { return loadController; }
    public AdaptiveBudgetController budgetController() { return budgetController; }
    public SpikeGuard spikeGuard() { return spikeGuard; }
    public RecoveryController recoveryController() { return recoveryController; }
    public JoinScheduler scheduler() { return scheduler; }
    public JoinProfiler profiler() { return profiler; }
    public CompatibilityManager compatibilityManager() { return compatibilityManager; }
    public JoinFlowNetworkScheduler networkScheduler() { return networkScheduler; }
    public long tickCounter() { return tickCounter; }
    public long queuedTaskEstimateMicros() { return scheduler.queuedEstimatedMicros(); }
    public int activeJoinSessions() { return scheduler.activeCount(); }
    public int queuedJoinSessions() { return scheduler.queuedSessionCount(); }

    private static int nextBatchId() {
        int result = NEXT_BATCH_ID.getAndIncrement();
        return result < 0 ? NEXT_BATCH_ID.updateAndGet(value -> Math.max(1, value)) : result;
    }

    private static String format(double value) {
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }

    private static void rethrowFatal(Throwable throwable) {
        if (throwable instanceof VirtualMachineError error) throw error;
        if (throwable instanceof ThreadDeath death) throw death;
    }
}
