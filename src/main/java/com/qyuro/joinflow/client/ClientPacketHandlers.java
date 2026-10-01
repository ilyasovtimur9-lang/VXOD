package com.qyuro.joinflow.client;

import com.qyuro.joinflow.network.packets.Packets;

/** Client-only packet dispatch target. Common packet DTOs reach this through DistExecutor. */
public final class ClientPacketHandlers {
    private ClientPacketHandlers() {
    }

    public static void handleHello(Packets.HelloPacket packet) {
        if (packet.compatible()) ClientJoinFlow.onHello(packet.heartbeatIntervalMs());
    }

    public static void handleState(Packets.JoinStatePacket packet) {
        if (packet.compatible()) ClientJoinFlow.onState(packet);
    }

    public static void handleProgress(Packets.JoinProgressPacket packet) {
        if (packet.compatible()) ClientJoinFlow.onProgress(packet);
    }

    public static void handleSyncBatch(Packets.SyncBatchPacket packet) {
        if (packet.compatible()) ClientSyncScheduler.accept(packet.batch());
    }

    public static void handleCacheInfo(Packets.CacheInfoPacket packet) {
        if (packet.compatible()) ClientJoinFlow.onCacheInfo(packet);
    }
}
