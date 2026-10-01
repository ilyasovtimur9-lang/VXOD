package com.qyuro.joinflow.client;

import com.qyuro.joinflow.QyuroJoinFlow;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = QyuroJoinFlow.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class ClientEvents {
    private ClientEvents() {
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        ClientCache.tickLoadCompletion();
        ClientJoinFlow.tick();
        ClientSyncScheduler.tick();
    }

    @SubscribeEvent
    public static void onClientLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientJoinFlow.disconnect();
        if (Minecraft.getInstance().screen instanceof JoinFlowScreen) Minecraft.getInstance().setScreen(null);
    }
}
