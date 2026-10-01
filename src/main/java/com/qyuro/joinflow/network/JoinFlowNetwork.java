package com.qyuro.joinflow.network;

import com.qyuro.joinflow.QyuroJoinFlow;
import com.qyuro.joinflow.network.packets.Packets;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.concurrent.atomic.AtomicBoolean;

/** Optional custom channel owned entirely by JoinFlow. */
public final class JoinFlowNetwork {
    public static final int PROTOCOL_VERSION = 1;
    public static final int MAX_PACKET_HARD_LIMIT = 1_048_576;
    public static final String PROTOCOL = Integer.toString(PROTOCOL_VERSION);
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(QyuroJoinFlow.MODID, "main"),
            () -> PROTOCOL,
            // The channel remains optional. An in-band, versioned Hello controls extension use.
            // Keep the small Hello envelope stable when introducing future protocol versions.
            remoteVersion -> true,
            remoteVersion -> true
    );

    private static final AtomicBoolean REGISTERED = new AtomicBoolean();

    private JoinFlowNetwork() {
    }

    public static void registerMessages() {
        if (REGISTERED.compareAndSet(false, true)) Packets.registerAll();
    }

    public static boolean isPresent(ServerPlayer player) {
        return player != null && CHANNEL.isRemotePresent(player.connection.connection);
    }

    public static void sendToPlayer(ServerPlayer player, Object packet) {
        if (player != null && isPresent(player)) {
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
        }
    }
}
