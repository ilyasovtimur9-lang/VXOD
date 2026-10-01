package com.qyuro.joinflow.client;

import com.qyuro.joinflow.QyuroJoinFlow;
import com.qyuro.joinflow.api.ClientSyncHandler;
import com.qyuro.joinflow.api.JoinFlowAPI;
import com.qyuro.joinflow.config.ClientConfig;
import com.qyuro.joinflow.network.SyncBatch;
import com.qyuro.joinflow.network.packets.Packets;

import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/** Client-thread chunk reassembly and paced processing for JoinFlow-owned static sync only. */
public final class ClientSyncScheduler {
    private static final int MAX_ACTIVE_BATCHES = 8;
    private static final Map<Integer, Assembly> ASSEMBLIES = new HashMap<>();
    private static final ArrayDeque<Assembly> READY = new ArrayDeque<>();
    private static long pendingBytes;
    private static long tickCounter;
    private static long lastProcessingMicros;

    private ClientSyncScheduler() {
    }

    public static void accept(SyncBatch batch) {
        if (!ClientConfig.enabled() || batch == null || batch.total() < 1 || batch.total() > 256
                || batch.sequence() < 0 || batch.sequence() >= batch.total()) return;
        byte[] payload = batch.copyPayload();
        if (payload.length > com.qyuro.joinflow.network.JoinFlowNetwork.MAX_PACKET_HARD_LIMIT
                || Packets.checksum(payload) != batch.checksum()) {
            ClientJoinFlow.sendSyncAck(batch.key(), batch.dataVersion(), batch.contentHash(), false);
            return;
        }

        Assembly assembly = ASSEMBLIES.get(batch.batchId());
        if (assembly == null) {
            if (ASSEMBLIES.size() >= MAX_ACTIVE_BATCHES || pendingBytes + payload.length > ClientConfig.maxCachedBytes()) {
                ClientJoinFlow.sendSyncAck(batch.key(), batch.dataVersion(), batch.contentHash(), false);
                return;
            }
            assembly = new Assembly(batch);
            ASSEMBLIES.put(batch.batchId(), assembly);
        } else if (!assembly.matches(batch)) {
            failAssembly(assembly);
            return;
        }

        if (assembly.parts[batch.sequence()] != null) return;
        if (pendingBytes + payload.length > ClientConfig.maxCachedBytes()) {
            failAssembly(assembly);
            return;
        }
        assembly.parts[batch.sequence()] = payload;
        assembly.received++;
        assembly.bytes += payload.length;
        pendingBytes += payload.length;
        if (assembly.received == assembly.total && !assembly.queuedForProcessing) {
            assembly.queuedForProcessing = true;
            READY.addLast(assembly);
        }
        updateTelemetry();
    }

    public static void tick() {
        tickCounter++;
        long budgetNanos = (long) (ClientConfig.clientTickBudgetMs() * 1_000_000.0);
        long started = System.nanoTime();
        while (!READY.isEmpty() && System.nanoTime() - started < budgetNanos) {
            Assembly assembly = READY.removeFirst();
            if (assembly.nextPart < assembly.total) {
                byte[] part = assembly.parts[assembly.nextPart++];
                assembly.output.write(part, 0, part.length);
                assembly.digest.update(part);
                READY.addLast(assembly);
                continue;
            }

            boolean success = false;
            byte[] payload = assembly.output.toByteArray();
            String actualHash = java.util.HexFormat.of().formatHex(assembly.digest.digest());
            if (actualHash.equals(assembly.hash)) {
                try {
                    ClientSyncHandler handler = JoinFlowAPI.clientSyncHandler(assembly.key);
                    if (handler != null) handler.apply(assembly.version, payload.clone());
                    if (assembly.cacheable) ClientCache.putVerified(assembly.key, assembly.version, assembly.hash, payload);
                    success = true;
                } catch (Throwable throwable) {
                    if (throwable instanceof VirtualMachineError error) throw error;
                    if (throwable instanceof ThreadDeath death) throw death;
                    QyuroJoinFlow.LOGGER.error("[QyuroJoinFlow] ERROR component=client-sync key={} exception=client handler failed",
                            assembly.key, throwable);
                }
            } else {
                QyuroJoinFlow.LOGGER.warn("[QyuroJoinFlow] Client rejected static sync with a mismatched hash (key={})", assembly.key);
            }
            finishAssembly(assembly, success);
        }

        long elapsed = System.nanoTime() - started;
        lastProcessingMicros = Math.max(0L, elapsed / 1000L);
        ClientJoinFlow.telemetry().recordBatchProcessing(elapsed);
        updateTelemetry();
        if (tickCounter % 100 == 0) expireIncompleteBatches();
    }

    private static void finishAssembly(Assembly assembly, boolean success) {
        ASSEMBLIES.remove(assembly.batchId);
        pendingBytes = Math.max(0L, pendingBytes - assembly.bytes);
        ClientJoinFlow.sendSyncAck(assembly.key, assembly.version, assembly.hash, success);
    }

    private static void failAssembly(Assembly assembly) {
        READY.remove(assembly);
        ASSEMBLIES.remove(assembly.batchId);
        pendingBytes = Math.max(0L, pendingBytes - assembly.bytes);
        ClientJoinFlow.sendSyncAck(assembly.key, assembly.version, assembly.hash, false);
        updateTelemetry();
    }

    private static void expireIncompleteBatches() {
        long now = System.nanoTime();
        Iterator<Assembly> iterator = ASSEMBLIES.values().iterator();
        while (iterator.hasNext()) {
            Assembly assembly = iterator.next();
            if (now - assembly.createdNanos <= 120_000_000_000L) continue;
            READY.remove(assembly);
            pendingBytes = Math.max(0L, pendingBytes - assembly.bytes);
            iterator.remove();
            ClientJoinFlow.sendSyncAck(assembly.key, assembly.version, assembly.hash, false);
        }
        updateTelemetry();
    }

    private static void updateTelemetry() {
        ClientJoinFlow.telemetry().setSyncQueueDepth(Math.min(256, ASSEMBLIES.size()));
    }

    public static void clear() {
        ASSEMBLIES.clear();
        READY.clear();
        pendingBytes = 0L;
        lastProcessingMicros = 0L;
        updateTelemetry();
    }

    public static int queueDepth() { return ASSEMBLIES.size() + READY.size(); }
    public static long lastProcessingMicros() { return lastProcessingMicros; }

    private static final class Assembly {
        private final int batchId;
        private final int total;
        private final String key;
        private final int version;
        private final String hash;
        private final boolean cacheable;
        private final byte[][] parts;
        private final long createdNanos = System.nanoTime();
        private final ByteArrayOutputStream output = new ByteArrayOutputStream(32768);
        private final MessageDigest digest;
        private int received;
        private int nextPart;
        private long bytes;
        private boolean queuedForProcessing;

        private Assembly(SyncBatch first) {
            batchId = first.batchId();
            total = first.total();
            key = first.key();
            version = first.dataVersion();
            hash = first.contentHash();
            cacheable = first.cacheable();
            parts = new byte[total][];
            try {
                digest = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException exception) {
                throw new IllegalStateException("SHA-256 is required by the Java runtime", exception);
            }
        }

        private boolean matches(SyncBatch batch) {
            return total == batch.total() && key.equals(batch.key()) && version == batch.dataVersion()
                    && hash.equals(batch.contentHash()) && cacheable == batch.cacheable();
        }
    }

}
