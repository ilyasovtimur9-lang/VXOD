package com.qyuro.joinflow.client;

import com.qyuro.joinflow.api.JoinPhase;
import com.qyuro.joinflow.config.ClientConfig;
import com.qyuro.joinflow.network.JoinFlowNetwork;
import com.qyuro.joinflow.network.packets.Packets;
import com.qyuro.joinflow.server.LoadState;
import net.minecraft.client.Minecraft;

/** Client companion state and low-rate, advisory-only protocol responses. */
public final class ClientJoinFlow {
    private static final ClientTelemetry TELEMETRY = new ClientTelemetry();
    private static boolean helloReceived;
    private static boolean readySent;
    private static boolean active;
    private static boolean screenDismissed;
    private static int clientSequence;
    private static int lastStateSequence = -1;
    private static int lastProgressSequence = -1;
    private static int progressPercent;
    private static int tasksCompleted;
    private static int tasksPending;
    private static long estimatedRemainingMicros;
    private static long serverHeartbeatIntervalMs = 1000L;
    private static long nextHeartbeatNanos;
    private static JoinPhase phase = JoinPhase.POST_JOIN;
    private static LoadState loadState = LoadState.NORMAL;
    private static String fallbackMessage = "Preparing connection";

    private ClientJoinFlow() {
    }

    public static void onHello(int heartbeatIntervalMs) {
        if (!ClientConfig.enabled()) return;
        if (!helloReceived || !active) {
            readySent = false;
            clientSequence = 0;
            lastStateSequence = -1;
            lastProgressSequence = -1;
            active = false;
            screenDismissed = false;
            nextHeartbeatNanos = 0L;
            TELEMETRY.setReady(false);
        }
        helloReceived = true;
        serverHeartbeatIntervalMs = Math.max(500L, Math.min(10000L, heartbeatIntervalMs));
        TELEMETRY.setCacheReady(ClientCache.isReady());
        JoinFlowNetwork.CHANNEL.sendToServer(new Packets.HelloAckPacket());
    }

    public static void onState(Packets.JoinStatePacket packet) {
        if (!ClientConfig.enabled() || packet.sequence() <= lastStateSequence) return;
        lastStateSequence = packet.sequence();
        phase = packet.phase();
        loadState = packet.loadState();
        active = phase != JoinPhase.COMPLETE;
        updateViewState();
    }

    public static void onProgress(Packets.JoinProgressPacket packet) {
        if (!ClientConfig.enabled() || packet.sequence() <= lastProgressSequence) return;
        lastProgressSequence = packet.sequence();
        phase = packet.phase();
        progressPercent = Math.max(0, Math.min(100, packet.progressPercent()));
        tasksCompleted = packet.tasksCompleted();
        tasksPending = packet.tasksPending();
        estimatedRemainingMicros = packet.estimatedRemainingMicros();
        active = phase != JoinPhase.COMPLETE;
        updateViewState();
    }

    private static void updateViewState() {
        if (active) fallbackMessage = "Server is distributing join-related work for stable gameplay.";
        else fallbackMessage = "Join preparation complete.";
    }

    public static void onCacheInfo(Packets.CacheInfoPacket packet) {
        if (!ClientConfig.enabled() || !helloReceived) return;
        boolean match = packet.cacheable() && ClientCache.contains(packet.key(), packet.version(), packet.hash());
        TELEMETRY.setCacheReady(ClientCache.isReady());
        JoinFlowNetwork.CHANNEL.sendToServer(new Packets.CacheResultPacket(nextSequence(), packet.key(),
                packet.version(), packet.hash(), match));
    }

    public static void sendSyncAck(String key, int version, String hash, boolean applied) {
        if (!helloReceived || !ClientConfig.enabled()) return;
        JoinFlowNetwork.CHANNEL.sendToServer(new Packets.SyncAckPacket(nextSequence(), key, version, hash, applied));
    }

    public static void requestSync(String key) {
        if (!helloReceived || !ClientConfig.enabled()) return;
        JoinFlowNetwork.CHANNEL.sendToServer(new Packets.SyncRequestPacket(nextSequence(), key, 0, ""));
    }

    public static void tick() {
        if (!ClientConfig.enabled() || !helloReceived) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null && !readySent) {
            readySent = true;
            TELEMETRY.setReady(true);
            JoinFlowNetwork.CHANNEL.sendToServer(new Packets.ClientReadyPacket(nextSequence(), true));
            nextHeartbeatNanos = System.nanoTime();
        }

        if (active && ClientConfig.uiEnabled() && ClientConfig.openScreen() && !screenDismissed
                && minecraft.player != null && minecraft.screen == null) {
            minecraft.setScreen(new JoinFlowScreen());
        } else if ((!active || !ClientConfig.uiEnabled() || !ClientConfig.openScreen())
                && minecraft.screen instanceof JoinFlowScreen) {
            minecraft.setScreen(null);
        }

        if (!readySent || !active || !ClientConfig.telemetryEnabled()) return;
        long intervalMs = Math.max(serverHeartbeatIntervalMs, ClientConfig.heartbeatIntervalMs());
        long now = System.nanoTime();
        if (now < nextHeartbeatNanos) return;
        nextHeartbeatNanos = now + intervalMs * 1_000_000L;
        JoinFlowNetwork.CHANNEL.sendToServer(new Packets.ClientHeartbeatPacket(nextSequence()));
        JoinFlowNetwork.CHANNEL.sendToServer(new Packets.ClientStatusPacket(nextSequence(),
                ClientSyncScheduler.queueDepth(), ClientSyncScheduler.lastProcessingMicros()));
    }

    private static int nextSequence() {
        if (clientSequence == Integer.MAX_VALUE) clientSequence = 0;
        return clientSequence++;
    }

    public static void disconnect() {
        helloReceived = false;
        readySent = false;
        active = false;
        screenDismissed = false;
        clientSequence = 0;
        lastStateSequence = -1;
        lastProgressSequence = -1;
        progressPercent = 0;
        tasksCompleted = 0;
        tasksPending = 0;
        estimatedRemainingMicros = 0L;
        TELEMETRY.setReady(false);
        ClientSyncScheduler.clear();
    }

    public static void dismissScreen() { screenDismissed = true; }

    public static boolean isActive() { return active; }
    public static boolean helloReceived() { return helloReceived; }
    public static int progressPercent() { return progressPercent; }
    public static int tasksCompleted() { return tasksCompleted; }
    public static int tasksPending() { return tasksPending; }
    public static long estimatedRemainingMicros() { return estimatedRemainingMicros; }
    public static JoinPhase phase() { return phase; }
    public static LoadState loadState() { return loadState; }
    public static String message() { return fallbackMessage; }
    public static ClientTelemetry telemetry() { return TELEMETRY; }
}
