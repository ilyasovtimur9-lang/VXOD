package com.qyuro.joinflow.network.packets;

import com.qyuro.joinflow.api.JoinPhase;
import com.qyuro.joinflow.network.JoinFlowNetwork;
import com.qyuro.joinflow.network.SyncBatch;
import com.qyuro.joinflow.server.LoadState;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;
import java.util.zip.CRC32;

/** Versioned wire DTOs. Decoders validate bounds before allocating variable-size data. */
public final class Packets {
    public static final int MESSAGE_VERSION = 1;
    private static final int MAX_KEY_LENGTH = 161;
    private static final int MAX_HASH_LENGTH = 64;

    private Packets() {
    }

    public static void registerAll() {
        int id = 0;
        JoinFlowNetwork.CHANNEL.registerMessage(id++, HelloPacket.class, HelloPacket::encode, HelloPacket::decode, HelloPacket::handle);
        JoinFlowNetwork.CHANNEL.registerMessage(id++, HelloAckPacket.class, HelloAckPacket::encode, HelloAckPacket::decode, HelloAckPacket::handle);
        JoinFlowNetwork.CHANNEL.registerMessage(id++, JoinStatePacket.class, JoinStatePacket::encode, JoinStatePacket::decode, JoinStatePacket::handle);
        JoinFlowNetwork.CHANNEL.registerMessage(id++, JoinProgressPacket.class, JoinProgressPacket::encode, JoinProgressPacket::decode, JoinProgressPacket::handle);
        JoinFlowNetwork.CHANNEL.registerMessage(id++, ClientReadyPacket.class, ClientReadyPacket::encode, ClientReadyPacket::decode, ClientReadyPacket::handle);
        JoinFlowNetwork.CHANNEL.registerMessage(id++, ClientStatusPacket.class, ClientStatusPacket::encode, ClientStatusPacket::decode, ClientStatusPacket::handle);
        JoinFlowNetwork.CHANNEL.registerMessage(id++, ClientHeartbeatPacket.class, ClientHeartbeatPacket::encode, ClientHeartbeatPacket::decode, ClientHeartbeatPacket::handle);
        JoinFlowNetwork.CHANNEL.registerMessage(id++, SyncBatchPacket.class, SyncBatchPacket::encode, SyncBatchPacket::decode, SyncBatchPacket::handle);
        JoinFlowNetwork.CHANNEL.registerMessage(id++, SyncRequestPacket.class, SyncRequestPacket::encode, SyncRequestPacket::decode, SyncRequestPacket::handle);
        JoinFlowNetwork.CHANNEL.registerMessage(id++, SyncAckPacket.class, SyncAckPacket::encode, SyncAckPacket::decode, SyncAckPacket::handle);
        JoinFlowNetwork.CHANNEL.registerMessage(id++, CacheInfoPacket.class, CacheInfoPacket::encode, CacheInfoPacket::decode, CacheInfoPacket::handle);
        JoinFlowNetwork.CHANNEL.registerMessage(id, CacheResultPacket.class, CacheResultPacket::encode, CacheResultPacket::decode, CacheResultPacket::handle);
    }

    private static Header readHeader(FriendlyByteBuf buffer) {
        return new Header(buffer.readVarInt(), buffer.readVarInt());
    }

    private static int readBoundedVarInt(FriendlyByteBuf buffer, int min, int max, String field) {
        int value = buffer.readVarInt();
        if (value < min || value > max) throw new DecoderException("Invalid " + field + ": " + value);
        return value;
    }

    private static long readBoundedLong(FriendlyByteBuf buffer, long min, long max, String field) {
        long value = buffer.readLong();
        if (value < min || value > max) throw new DecoderException("Invalid " + field + ": " + value);
        return value;
    }

    private static String readKey(FriendlyByteBuf buffer) {
        String value = buffer.readUtf(MAX_KEY_LENGTH);
        if (value.isBlank() || !value.matches("[a-z0-9_.-]{1,64}:[a-z0-9_./-]{1,96}")) {
            throw new DecoderException("Invalid JoinFlow key");
        }
        return value;
    }

    private static String readHash(FriendlyByteBuf buffer) {
        String value = buffer.readUtf(MAX_HASH_LENGTH);
        if (value.length() != 64 || !value.matches("[0-9a-f]{64}")) {
            throw new DecoderException("Invalid SHA-256 hash");
        }
        return value;
    }

    private static void serverWork(Supplier<NetworkEvent.Context> supplier,
                                   java.util.function.Consumer<NetworkEvent.Context> action) {
        NetworkEvent.Context context = supplier.get();
        if (context.getDirection() == NetworkDirection.PLAY_TO_SERVER && context.getSender() != null) {
            context.enqueueWork(() -> action.accept(context));
        }
        context.setPacketHandled(true);
    }

    private static void clientWork(Supplier<NetworkEvent.Context> supplier,
                                   Supplier<Runnable> clientActionFactory) {
        NetworkEvent.Context context = supplier.get();
        if (context.getDirection() == NetworkDirection.PLAY_TO_CLIENT) {
            context.enqueueWork(() -> net.minecraftforge.fml.DistExecutor.unsafeRunWhenOn(
                    net.minecraftforge.api.distmarker.Dist.CLIENT, clientActionFactory));
        }
        context.setPacketHandled(true);
    }

    private static void withRuntime(net.minecraft.server.level.ServerPlayer player,
                                    java.util.function.BiConsumer<com.qyuro.joinflow.server.JoinFlowRuntime,
                                            net.minecraft.server.level.ServerPlayer> action) {
        if (player == null) return;
        com.qyuro.joinflow.server.JoinFlowRuntime runtime = com.qyuro.joinflow.server.JoinFlowRuntime.current();
        if (runtime != null) action.accept(runtime, player);
    }

    private record Header(int protocolVersion, int messageVersion) { }

    public abstract static class VersionedMessage {
        private final int protocolVersion;
        private final int messageVersion;

        protected VersionedMessage() {
            this(JoinFlowNetwork.PROTOCOL_VERSION, MESSAGE_VERSION);
        }

        protected VersionedMessage(Header header) {
            this(header.protocolVersion(), header.messageVersion());
        }

        protected VersionedMessage(int protocolVersion, int messageVersion) {
            this.protocolVersion = protocolVersion;
            this.messageVersion = messageVersion;
        }

        protected void encodeHeader(FriendlyByteBuf buffer) {
            buffer.writeVarInt(protocolVersion);
            buffer.writeVarInt(messageVersion);
        }

        public boolean compatible() {
            return protocolVersion == JoinFlowNetwork.PROTOCOL_VERSION && messageVersion == MESSAGE_VERSION;
        }
    }

    public static final class HelloPacket extends VersionedMessage {
        private final int heartbeatIntervalMs;

        public HelloPacket(int heartbeatIntervalMs) {
            this.heartbeatIntervalMs = Math.max(500, Math.min(10000, heartbeatIntervalMs));
        }

        private HelloPacket(Header header, int heartbeatIntervalMs) {
            super(header);
            this.heartbeatIntervalMs = Math.max(500, Math.min(10000, heartbeatIntervalMs));
        }

        public int heartbeatIntervalMs() { return heartbeatIntervalMs; }
        public void encode(FriendlyByteBuf buffer) { encodeHeader(buffer); buffer.writeVarInt(heartbeatIntervalMs); }
        public static HelloPacket decode(FriendlyByteBuf buffer) {
            Header header = readHeader(buffer);
            int interval = readBoundedVarInt(buffer, 500, 10000, "heartbeat interval");
            return new HelloPacket(header, interval);
        }
        public static void handle(HelloPacket packet, Supplier<NetworkEvent.Context> context) {
            clientWork(context, () -> () -> com.qyuro.joinflow.client.ClientPacketHandlers.handleHello(packet));
        }
    }

    public static final class HelloAckPacket extends VersionedMessage {
        public HelloAckPacket() { }
        private HelloAckPacket(Header header) { super(header); }
        public void encode(FriendlyByteBuf buffer) { encodeHeader(buffer); }
        public static HelloAckPacket decode(FriendlyByteBuf buffer) { return new HelloAckPacket(readHeader(buffer)); }
        public static void handle(HelloAckPacket packet, Supplier<NetworkEvent.Context> context) {
            serverWork(context, ctx -> {
                if (packet.compatible()) withRuntime(ctx.getSender(), (runtime, player) -> runtime.onHelloAck(player));
            });
        }
    }

    public static final class JoinStatePacket extends VersionedMessage {
        private final int sequence;
        private final JoinPhase phase;
        private final LoadState loadState;

        public JoinStatePacket(int sequence, JoinPhase phase, LoadState loadState) {
            this.sequence = sequence;
            this.phase = phase;
            this.loadState = loadState;
        }
        private JoinStatePacket(Header header, int sequence, JoinPhase phase, LoadState loadState) {
            super(header); this.sequence = sequence; this.phase = phase; this.loadState = loadState;
        }
        public int sequence() { return sequence; }
        public JoinPhase phase() { return phase; }
        public LoadState loadState() { return loadState; }
        public void encode(FriendlyByteBuf buffer) {
            encodeHeader(buffer); buffer.writeVarInt(sequence); buffer.writeByte(phase.ordinal()); buffer.writeByte(loadState.ordinal());
        }
        public static JoinStatePacket decode(FriendlyByteBuf buffer) {
            Header header = readHeader(buffer);
            int sequence = readBoundedVarInt(buffer, 0, Integer.MAX_VALUE, "sequence");
            int phase = buffer.readUnsignedByte();
            int state = buffer.readUnsignedByte();
            if (phase >= JoinPhase.values().length || state >= LoadState.values().length) {
                throw new DecoderException("Invalid state enum in JoinStatePacket");
            }
            return new JoinStatePacket(header, sequence, JoinPhase.values()[phase], LoadState.values()[state]);
        }
        public static void handle(JoinStatePacket packet, Supplier<NetworkEvent.Context> context) {
            clientWork(context, () -> () -> com.qyuro.joinflow.client.ClientPacketHandlers.handleState(packet));
        }
    }

    public static final class JoinProgressPacket extends VersionedMessage {
        private final int sequence;
        private final JoinPhase phase;
        private final int progressPercent;
        private final int tasksCompleted;
        private final int tasksPending;
        private final long estimatedRemainingMicros;

        public JoinProgressPacket(int sequence, JoinPhase phase, int progressPercent,
                                  int tasksCompleted, int tasksPending, long estimatedRemainingMicros) {
            this.sequence = sequence;
            this.phase = phase;
            this.progressPercent = Math.max(0, Math.min(100, progressPercent));
            this.tasksCompleted = Math.max(0, tasksCompleted);
            this.tasksPending = Math.max(0, tasksPending);
            this.estimatedRemainingMicros = Math.max(0L, estimatedRemainingMicros);
        }
        private JoinProgressPacket(Header header, int sequence, JoinPhase phase, int progressPercent,
                                   int tasksCompleted, int tasksPending, long estimatedRemainingMicros) {
            super(header); this.sequence = sequence; this.phase = phase;
            this.progressPercent = progressPercent; this.tasksCompleted = tasksCompleted;
            this.tasksPending = tasksPending; this.estimatedRemainingMicros = estimatedRemainingMicros;
        }
        public int sequence() { return sequence; }
        public JoinPhase phase() { return phase; }
        public int progressPercent() { return progressPercent; }
        public int tasksCompleted() { return tasksCompleted; }
        public int tasksPending() { return tasksPending; }
        public long estimatedRemainingMicros() { return estimatedRemainingMicros; }
        public void encode(FriendlyByteBuf buffer) {
            encodeHeader(buffer); buffer.writeVarInt(sequence); buffer.writeByte(phase.ordinal());
            buffer.writeByte(progressPercent); buffer.writeVarInt(tasksCompleted); buffer.writeVarInt(tasksPending);
            buffer.writeLong(estimatedRemainingMicros);
        }
        public static JoinProgressPacket decode(FriendlyByteBuf buffer) {
            Header header = readHeader(buffer);
            int sequence = readBoundedVarInt(buffer, 0, Integer.MAX_VALUE, "sequence");
            int phase = buffer.readUnsignedByte();
            int progress = readBoundedVarInt(buffer, 0, 100, "progress");
            int completed = readBoundedVarInt(buffer, 0, 100000, "completed tasks");
            int pending = readBoundedVarInt(buffer, 0, 100000, "pending tasks");
            long remaining = readBoundedLong(buffer, 0L, 3_600_000_000L, "remaining estimate");
            if (phase >= JoinPhase.values().length) throw new DecoderException("Invalid JoinFlow phase");
            return new JoinProgressPacket(header, sequence, JoinPhase.values()[phase], progress, completed, pending, remaining);
        }
        public static void handle(JoinProgressPacket packet, Supplier<NetworkEvent.Context> context) {
            clientWork(context, () -> () -> com.qyuro.joinflow.client.ClientPacketHandlers.handleProgress(packet));
        }
    }

    public static final class ClientReadyPacket extends VersionedMessage {
        private final int sequence;
        private final boolean ready;
        public ClientReadyPacket(int sequence, boolean ready) { this.sequence = sequence; this.ready = ready; }
        private ClientReadyPacket(Header header, int sequence, boolean ready) { super(header); this.sequence = sequence; this.ready = ready; }
        public int sequence() { return sequence; }
        public boolean ready() { return ready; }
        public void encode(FriendlyByteBuf buffer) { encodeHeader(buffer); buffer.writeVarInt(sequence); buffer.writeBoolean(ready); }
        public static ClientReadyPacket decode(FriendlyByteBuf buffer) {
            Header header = readHeader(buffer);
            return new ClientReadyPacket(header, readBoundedVarInt(buffer, 0, Integer.MAX_VALUE, "sequence"), buffer.readBoolean());
        }
        public static void handle(ClientReadyPacket packet, Supplier<NetworkEvent.Context> context) {
            serverWork(context, ctx -> {
                if (packet.compatible()) withRuntime(ctx.getSender(),
                        (runtime, player) -> runtime.onClientReady(player, packet.sequence(), packet.ready()));
            });
        }
    }

    public static final class ClientStatusPacket extends VersionedMessage {
        private final int sequence;
        private final int queueDepth;
        private final long processingMicros;
        public ClientStatusPacket(int sequence, int queueDepth, long processingMicros) {
            this.sequence = sequence; this.queueDepth = Math.max(0, Math.min(256, queueDepth));
            this.processingMicros = Math.max(0L, Math.min(10_000_000L, processingMicros));
        }
        private ClientStatusPacket(Header header, int sequence, int queueDepth, long processingMicros) {
            super(header); this.sequence = sequence; this.queueDepth = queueDepth; this.processingMicros = processingMicros;
        }
        public int sequence() { return sequence; }
        public int queueDepth() { return queueDepth; }
        public long processingMicros() { return processingMicros; }
        public void encode(FriendlyByteBuf buffer) {
            encodeHeader(buffer); buffer.writeVarInt(sequence); buffer.writeVarInt(queueDepth); buffer.writeLong(processingMicros);
        }
        public static ClientStatusPacket decode(FriendlyByteBuf buffer) {
            Header header = readHeader(buffer);
            return new ClientStatusPacket(header, readBoundedVarInt(buffer, 0, Integer.MAX_VALUE, "sequence"),
                    readBoundedVarInt(buffer, 0, 256, "client queue depth"),
                    readBoundedLong(buffer, 0L, 10_000_000L, "client processing time"));
        }
        public static void handle(ClientStatusPacket packet, Supplier<NetworkEvent.Context> context) {
            serverWork(context, ctx -> {
                if (packet.compatible()) withRuntime(ctx.getSender(), (runtime, player) ->
                        runtime.onClientStatus(player, packet.sequence(), packet.queueDepth(), packet.processingMicros()));
            });
        }
    }

    public static final class ClientHeartbeatPacket extends VersionedMessage {
        private final int sequence;
        public ClientHeartbeatPacket(int sequence) { this.sequence = sequence; }
        private ClientHeartbeatPacket(Header header, int sequence) { super(header); this.sequence = sequence; }
        public int sequence() { return sequence; }
        public void encode(FriendlyByteBuf buffer) { encodeHeader(buffer); buffer.writeVarInt(sequence); }
        public static ClientHeartbeatPacket decode(FriendlyByteBuf buffer) {
            Header header = readHeader(buffer);
            return new ClientHeartbeatPacket(header, readBoundedVarInt(buffer, 0, Integer.MAX_VALUE, "sequence"));
        }
        public static void handle(ClientHeartbeatPacket packet, Supplier<NetworkEvent.Context> context) {
            serverWork(context, ctx -> {
                if (packet.compatible()) withRuntime(ctx.getSender(), (runtime, player) -> runtime.onHeartbeat(player, packet.sequence()));
            });
        }
    }

    public static final class SyncBatchPacket extends VersionedMessage {
        private final SyncBatch batch;
        public SyncBatchPacket(SyncBatch batch) { this.batch = batch; }
        private SyncBatchPacket(Header header, SyncBatch batch) { super(header); this.batch = batch; }
        public SyncBatch batch() { return batch; }
        public void encode(FriendlyByteBuf buffer) {
            encodeHeader(buffer);
            byte[] payload = batch.copyPayload();
            buffer.writeVarInt(batch.batchId()); buffer.writeVarInt(batch.sequence()); buffer.writeVarInt(batch.total());
            buffer.writeUtf(batch.key(), MAX_KEY_LENGTH); buffer.writeVarInt(batch.dataVersion());
            buffer.writeUtf(batch.contentHash(), MAX_HASH_LENGTH); buffer.writeInt(batch.checksum());
            buffer.writeBoolean(batch.cacheable()); buffer.writeVarInt(payload.length); buffer.writeBytes(payload);
        }
        public static SyncBatchPacket decode(FriendlyByteBuf buffer) {
            Header header = readHeader(buffer);
            int batchId = readBoundedVarInt(buffer, 0, Integer.MAX_VALUE, "batch id");
            int sequence = readBoundedVarInt(buffer, 0, 255, "batch sequence");
            int total = readBoundedVarInt(buffer, 1, 256, "batch total");
            if (sequence >= total) throw new DecoderException("Batch sequence is outside total");
            String key = readKey(buffer);
            int version = readBoundedVarInt(buffer, 0, Integer.MAX_VALUE, "data version");
            String hash = readHash(buffer);
            int checksum = buffer.readInt();
            boolean cacheable = buffer.readBoolean();
            int length = readBoundedVarInt(buffer, 0, JoinFlowNetwork.MAX_PACKET_HARD_LIMIT, "payload length");
            if (length > buffer.readableBytes()) throw new DecoderException("Truncated JoinFlow payload");
            byte[] payload = new byte[length];
            buffer.readBytes(payload);
            return new SyncBatchPacket(header, new SyncBatch(batchId, sequence, total, key, version,
                    hash, checksum, cacheable, payload));
        }
        public static void handle(SyncBatchPacket packet, Supplier<NetworkEvent.Context> context) {
            clientWork(context, () -> () -> com.qyuro.joinflow.client.ClientPacketHandlers.handleSyncBatch(packet));
        }
    }

    public static final class SyncRequestPacket extends VersionedMessage {
        private final int sequence;
        private final String key;
        private final int knownVersion;
        private final String knownHash;
        public SyncRequestPacket(int sequence, String key, int knownVersion, String knownHash) {
            this.sequence = sequence; this.key = key; this.knownVersion = knownVersion; this.knownHash = knownHash;
        }
        private SyncRequestPacket(Header header, int sequence, String key, int knownVersion, String knownHash) {
            super(header); this.sequence = sequence; this.key = key; this.knownVersion = knownVersion; this.knownHash = knownHash;
        }
        public int sequence() { return sequence; }
        public String key() { return key; }
        public int knownVersion() { return knownVersion; }
        public String knownHash() { return knownHash; }
        public void encode(FriendlyByteBuf buffer) {
            encodeHeader(buffer); buffer.writeVarInt(sequence); buffer.writeUtf(key, MAX_KEY_LENGTH);
            buffer.writeVarInt(knownVersion); buffer.writeUtf(knownHash, MAX_HASH_LENGTH);
        }
        public static SyncRequestPacket decode(FriendlyByteBuf buffer) {
            Header header = readHeader(buffer);
            int sequence = readBoundedVarInt(buffer, 0, Integer.MAX_VALUE, "sequence");
            String key = readKey(buffer);
            int version = readBoundedVarInt(buffer, 0, Integer.MAX_VALUE, "known version");
            String hash = buffer.readUtf(MAX_HASH_LENGTH);
            if (!hash.isEmpty() && (hash.length() != 64 || !hash.matches("[0-9a-f]{64}"))) {
                throw new DecoderException("Invalid known hash");
            }
            return new SyncRequestPacket(header, sequence, key, version, hash);
        }
        public static void handle(SyncRequestPacket packet, Supplier<NetworkEvent.Context> context) {
            serverWork(context, ctx -> {
                if (packet.compatible()) withRuntime(ctx.getSender(), (runtime, player) ->
                        runtime.onSyncRequest(player, packet.sequence(), packet.key(), packet.knownVersion(), packet.knownHash()));
            });
        }
    }

    public static final class SyncAckPacket extends VersionedMessage {
        private final int sequence;
        private final String key;
        private final int version;
        private final String hash;
        private final boolean applied;
        public SyncAckPacket(int sequence, String key, int version, String hash, boolean applied) {
            this.sequence = sequence; this.key = key; this.version = version; this.hash = hash; this.applied = applied;
        }
        private SyncAckPacket(Header header, int sequence, String key, int version, String hash, boolean applied) {
            super(header); this.sequence = sequence; this.key = key; this.version = version; this.hash = hash; this.applied = applied;
        }
        public int sequence() { return sequence; }
        public String key() { return key; }
        public int version() { return version; }
        public String hash() { return hash; }
        public boolean applied() { return applied; }
        public void encode(FriendlyByteBuf buffer) {
            encodeHeader(buffer); buffer.writeVarInt(sequence); buffer.writeUtf(key, MAX_KEY_LENGTH);
            buffer.writeVarInt(version); buffer.writeUtf(hash, MAX_HASH_LENGTH); buffer.writeBoolean(applied);
        }
        public static SyncAckPacket decode(FriendlyByteBuf buffer) {
            Header header = readHeader(buffer);
            return new SyncAckPacket(header, readBoundedVarInt(buffer, 0, Integer.MAX_VALUE, "sequence"), readKey(buffer),
                    readBoundedVarInt(buffer, 0, Integer.MAX_VALUE, "data version"), readHash(buffer), buffer.readBoolean());
        }
        public static void handle(SyncAckPacket packet, Supplier<NetworkEvent.Context> context) {
            serverWork(context, ctx -> {
                if (packet.compatible()) withRuntime(ctx.getSender(), (runtime, player) ->
                        runtime.onSyncAck(player, packet.sequence(), packet.key(), packet.version(), packet.hash(), packet.applied()));
            });
        }
    }

    public static final class CacheInfoPacket extends VersionedMessage {
        private final String key;
        private final int version;
        private final String hash;
        private final boolean cacheable;
        public CacheInfoPacket(String key, int version, String hash, boolean cacheable) {
            this.key = key; this.version = version; this.hash = hash; this.cacheable = cacheable;
        }
        private CacheInfoPacket(Header header, String key, int version, String hash, boolean cacheable) {
            super(header); this.key = key; this.version = version; this.hash = hash; this.cacheable = cacheable;
        }
        public String key() { return key; }
        public int version() { return version; }
        public String hash() { return hash; }
        public boolean cacheable() { return cacheable; }
        public void encode(FriendlyByteBuf buffer) {
            encodeHeader(buffer); buffer.writeUtf(key, MAX_KEY_LENGTH); buffer.writeVarInt(version);
            buffer.writeUtf(hash, MAX_HASH_LENGTH); buffer.writeBoolean(cacheable);
        }
        public static CacheInfoPacket decode(FriendlyByteBuf buffer) {
            Header header = readHeader(buffer);
            return new CacheInfoPacket(header, readKey(buffer), readBoundedVarInt(buffer, 0, Integer.MAX_VALUE, "data version"),
                    readHash(buffer), buffer.readBoolean());
        }
        public static void handle(CacheInfoPacket packet, Supplier<NetworkEvent.Context> context) {
            clientWork(context, () -> () -> com.qyuro.joinflow.client.ClientPacketHandlers.handleCacheInfo(packet));
        }
    }

    public static final class CacheResultPacket extends VersionedMessage {
        private final int sequence;
        private final String key;
        private final int version;
        private final String hash;
        private final boolean matches;
        public CacheResultPacket(int sequence, String key, int version, String hash, boolean matches) {
            this.sequence = sequence; this.key = key; this.version = version; this.hash = hash; this.matches = matches;
        }
        private CacheResultPacket(Header header, int sequence, String key, int version, String hash, boolean matches) {
            super(header); this.sequence = sequence; this.key = key; this.version = version; this.hash = hash; this.matches = matches;
        }
        public int sequence() { return sequence; }
        public String key() { return key; }
        public int version() { return version; }
        public String hash() { return hash; }
        public boolean matches() { return matches; }
        public void encode(FriendlyByteBuf buffer) {
            encodeHeader(buffer); buffer.writeVarInt(sequence); buffer.writeUtf(key, MAX_KEY_LENGTH);
            buffer.writeVarInt(version); buffer.writeUtf(hash, MAX_HASH_LENGTH); buffer.writeBoolean(matches);
        }
        public static CacheResultPacket decode(FriendlyByteBuf buffer) {
            Header header = readHeader(buffer);
            return new CacheResultPacket(header, readBoundedVarInt(buffer, 0, Integer.MAX_VALUE, "sequence"), readKey(buffer),
                    readBoundedVarInt(buffer, 0, Integer.MAX_VALUE, "data version"), readHash(buffer), buffer.readBoolean());
        }
        public static void handle(CacheResultPacket packet, Supplier<NetworkEvent.Context> context) {
            serverWork(context, ctx -> {
                if (packet.compatible()) withRuntime(ctx.getSender(), (runtime, player) ->
                        runtime.onCacheResult(player, packet.sequence(), packet.key(), packet.version(), packet.hash(), packet.matches()));
            });
        }
    }

    public static int checksum(byte[] payload) {
        CRC32 crc = new CRC32();
        crc.update(payload);
        return (int) crc.getValue();
    }
}
