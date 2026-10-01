package com.qyuro.joinflow.network;

import com.qyuro.joinflow.QyuroJoinFlow;
import com.qyuro.joinflow.config.ServerConfig;
import com.qyuro.joinflow.network.packets.Packets;
import com.qyuro.joinflow.server.JoinQueue;
import com.qyuro.joinflow.server.JoinSession;
import com.qyuro.joinflow.server.LoadState;
import com.qyuro.joinflow.task.TaskPriority;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayDeque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.zip.CRC32;

/** Fair, bounded outbound queue for JoinFlow packets only. No Netty pipeline is modified. */
public final class JoinFlowNetworkScheduler {
    private final EnumMap<TaskPriority, PlayerPacketBucket> queues = new EnumMap<>(TaskPriority.class);
    private final Map<UUID, Integer> pendingBytesByPlayer = new HashMap<>();
    private final Map<String, Outbound> coalesced = new HashMap<>();
    private int size;
    private long totalBytesSent;
    private long totalPacketsSent;
    private long totalBatchesSent;
    private long rejectedPackets;

    public JoinFlowNetworkScheduler() {
        for (TaskPriority priority : TaskPriority.values()) queues.put(priority, new PlayerPacketBucket());
    }

    public boolean enqueue(UUID playerId, Object packet, TaskPriority priority, int estimatedBytes,
                           boolean requiresReady, String coalesceKey) {
        if (!ServerConfig.networkEnabled() || playerId == null || packet == null || priority == null) return false;
        int packetBytes = Math.max(1, estimatedBytes);
        if (packetBytes > ServerConfig.maxPacketBytes()) {
            rejectedPackets++;
            return false;
        }

        String scopedKey = coalesceKey == null ? null : playerId + "/" + coalesceKey;
        if (scopedKey != null) {
            Outbound replaced = coalesced.get(scopedKey);
            if (replaced != null) remove(replaced);
        }

        if (size >= ServerConfig.maxOutboundQueueEntries()
                || pendingBytesByPlayer.getOrDefault(playerId, 0) + packetBytes > ServerConfig.maxPendingBytesPerPlayer()) {
            rejectedPackets++;
            return false;
        }

        Outbound outbound = new Outbound(playerId, packet, priority, packetBytes, requiresReady, scopedKey);
        queues.get(priority).add(outbound);
        pendingBytesByPlayer.merge(playerId, packetBytes, Integer::sum);
        size++;
        if (scopedKey != null) coalesced.put(scopedKey, outbound);
        return true;
    }

    private void remove(Outbound outbound) {
        if (!queues.get(outbound.priority).remove(outbound)) return;
        size = Math.max(0, size - 1);
        subtractBytes(outbound.playerId, outbound.estimatedBytes);
        if (outbound.coalesceKey != null) coalesced.remove(outbound.coalesceKey, outbound);
    }

    public int tick(MinecraftServer server, JoinQueue sessions, LoadState state) {
        if (!ServerConfig.networkEnabled() || size == 0) return 0;
        int maxPackets = packetsPerTick(state);
        TaskPriority minimum = minimumPriority(state);
        int sent = 0;

        while (sent < maxPackets && size > 0) {
            Outbound outbound = poll(sessions, minimum);
            if (outbound == null) break;

            ServerPlayer player = server.getPlayerList().getPlayer(outbound.playerId);
            if (player == null || !JoinFlowNetwork.isPresent(player)) {
                continue;
            }
            JoinSession session = sessions.get(outbound.playerId);
            if (outbound.requiresReady && (session == null || !session.clientSupported() || !session.clientReady())) {
                // The player was not ready by the time this item reached the head; keep it bounded and retry later.
                requeue(outbound);
                break;
            }

            try {
                JoinFlowNetwork.sendToPlayer(player, outbound.packet);
                totalBytesSent += outbound.estimatedBytes;
                totalPacketsSent++;
                if (outbound.packet instanceof Packets.SyncBatchPacket) totalBatchesSent++;
                if (session != null) session.recordBytesSent(outbound.estimatedBytes);
                sent++;
            } catch (RuntimeException exception) {
                QyuroJoinFlow.LOGGER.warn("[QyuroJoinFlow] Own-channel send failed for player {}; dropping one optional JoinFlow message",
                        outbound.playerId, exception);
            }
        }
        return sent;
    }

    private Outbound poll(JoinQueue sessions, TaskPriority minimum) {
        for (TaskPriority priority : TaskPriority.values()) {
            if (priority.rank() < minimum.rank()) continue;
            Outbound outbound = queues.get(priority).pollEligible(candidate -> {
                if (!candidate.requiresReady) return true;
                JoinSession session = sessions.get(candidate.playerId);
                return session != null && session.clientSupported() && session.clientReady();
            });
            if (outbound != null) {
                size--;
                subtractBytes(outbound.playerId, outbound.estimatedBytes);
                if (outbound.coalesceKey != null) coalesced.remove(outbound.coalesceKey, outbound);
                return outbound;
            }
        }
        return null;
    }

    private void requeue(Outbound outbound) {
        queues.get(outbound.priority).addFront(outbound);
        pendingBytesByPlayer.merge(outbound.playerId, outbound.estimatedBytes, Integer::sum);
        size++;
        if (outbound.coalesceKey != null) coalesced.put(outbound.coalesceKey, outbound);
    }

    private void subtractBytes(UUID playerId, int bytes) {
        int remaining = pendingBytesByPlayer.getOrDefault(playerId, 0) - bytes;
        if (remaining <= 0) pendingBytesByPlayer.remove(playerId);
        else pendingBytesByPlayer.put(playerId, remaining);
    }

    private int packetsPerTick(LoadState state) {
        int configured = ServerConfig.maxOutboundPacketsPerTick();
        return switch (state) {
            case ECO, NORMAL, RECOVERY -> configured;
            case PROTECTED -> Math.max(1, configured / 2);
            case CRITICAL -> Math.min(2, configured);
            case EMERGENCY -> 1;
        };
    }

    private TaskPriority minimumPriority(LoadState state) {
        return switch (state) {
            case ECO, NORMAL, RECOVERY -> TaskPriority.BACKGROUND;
            case PROTECTED -> TaskPriority.LOW;
            case CRITICAL -> TaskPriority.NORMAL;
            case EMERGENCY -> TaskPriority.HIGH;
        };
    }

    public int enqueueStaticData(UUID playerId, SyncDataRegistry.StaticData data, int batchId) {
        byte[] full = data.copyPayload();
        int packetLimit = ServerConfig.maxPacketBytes();
        int payloadLimit = Math.max(1, packetLimit - 320);
        if (!ServerConfig.batchingEnabled() && full.length > payloadLimit) return 0;
        int total = ServerConfig.batchingEnabled() ? Math.max(1, (full.length + payloadLimit - 1) / payloadLimit) : 1;
        if (total > 256) return 0;

        long batchBytes = full.length + (long) total * 320L;
        if (batchBytes > ServerConfig.maxPendingBytesPerPlayer()
                || pendingBytesByPlayer.getOrDefault(playerId, 0) + batchBytes > ServerConfig.maxPendingBytesPerPlayer()
                || size + total > ServerConfig.maxOutboundQueueEntries()) return 0;
        int queued = 0;
        for (int sequence = 0; sequence < total; sequence++) {
            int from = sequence * payloadLimit;
            int to = Math.min(full.length, from + payloadLimit);
            byte[] part = java.util.Arrays.copyOfRange(full, from, to);
            CRC32 crc = new CRC32();
            crc.update(part);
            SyncBatch batch = new SyncBatch(batchId, sequence, total, data.key(), data.version(),
                    data.hash(), (int) crc.getValue(), data.cacheable(), part);
            Packets.SyncBatchPacket packet = new Packets.SyncBatchPacket(batch);
            int estimatedBytes = part.length + 320;
            if (enqueue(playerId, packet, TaskPriority.NORMAL, estimatedBytes, true, null)) queued++;
            else break;
        }
        return queued;
    }

    public int cancelPlayer(UUID playerId) {
        int removed = 0;
        for (PlayerPacketBucket bucket : queues.values()) {
            for (Outbound outbound : bucket.removePlayer(playerId)) {
                size = Math.max(0, size - 1);
                subtractBytes(playerId, outbound.estimatedBytes);
                if (outbound.coalesceKey != null) coalesced.remove(outbound.coalesceKey, outbound);
                removed++;
            }
        }
        return removed;
    }

    public void clear() {
        for (PlayerPacketBucket bucket : queues.values()) bucket.clear();
        pendingBytesByPlayer.clear();
        coalesced.clear();
        size = 0;
    }

    public int size() { return size; }
    public long totalBytesSent() { return totalBytesSent; }
    public long totalPacketsSent() { return totalPacketsSent; }
    public long totalBatchesSent() { return totalBatchesSent; }
    public long rejectedPackets() { return rejectedPackets; }

    private record Outbound(UUID playerId, Object packet, TaskPriority priority, int estimatedBytes,
                            boolean requiresReady, String coalesceKey) { }

    private static final class PlayerPacketBucket {
        private final LinkedHashMap<UUID, ArrayDeque<Outbound>> byPlayer = new LinkedHashMap<>();
        private final ArrayDeque<UUID> rotation = new ArrayDeque<>();

        private void add(Outbound outbound) {
            ArrayDeque<Outbound> playerQueue = byPlayer.get(outbound.playerId);
            if (playerQueue == null) {
                playerQueue = new ArrayDeque<>();
                byPlayer.put(outbound.playerId, playerQueue);
                rotation.addLast(outbound.playerId);
            }
            playerQueue.addLast(outbound);
        }

        private void addFront(Outbound outbound) {
            ArrayDeque<Outbound> playerQueue = byPlayer.get(outbound.playerId);
            if (playerQueue == null) {
                playerQueue = new ArrayDeque<>();
                byPlayer.put(outbound.playerId, playerQueue);
                rotation.addFirst(outbound.playerId);
            }
            playerQueue.addFirst(outbound);
        }

        private Outbound pollEligible(java.util.function.Predicate<Outbound> predicate) {
            int checks = rotation.size();
            for (int i = 0; i < checks; i++) {
                UUID player = rotation.removeFirst();
                ArrayDeque<Outbound> playerQueue = byPlayer.get(player);
                if (playerQueue == null || playerQueue.isEmpty()) {
                    byPlayer.remove(player);
                    continue;
                }
                Outbound candidate = playerQueue.peekFirst();
                rotation.addLast(player);
                if (!predicate.test(candidate)) continue;
                playerQueue.removeFirst();
                if (playerQueue.isEmpty()) {
                    byPlayer.remove(player);
                    rotation.removeLastOccurrence(player);
                }
                return candidate;
            }
            return null;
        }

        private boolean remove(Outbound outbound) {
            ArrayDeque<Outbound> playerQueue = byPlayer.get(outbound.playerId);
            if (playerQueue == null || !playerQueue.removeFirstOccurrence(outbound)) return false;
            if (playerQueue.isEmpty()) {
                byPlayer.remove(outbound.playerId);
                rotation.removeFirstOccurrence(outbound.playerId);
            }
            return true;
        }

        private java.util.List<Outbound> removePlayer(UUID playerId) {
            ArrayDeque<Outbound> playerQueue = byPlayer.remove(playerId);
            rotation.removeIf(playerId::equals);
            return playerQueue == null ? java.util.List.of() : new java.util.ArrayList<>(playerQueue);
        }

        private void clear() {
            byPlayer.clear();
            rotation.clear();
        }
    }
}
