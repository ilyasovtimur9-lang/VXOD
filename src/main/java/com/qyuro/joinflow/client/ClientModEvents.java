package com.qyuro.joinflow.client;

import com.qyuro.joinflow.QyuroJoinFlow;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

@Mod.EventBusSubscriber(modid = QyuroJoinFlow.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ClientModEvents {
    private ClientModEvents() {
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(ClientCache::initialize);
    }

    @SubscribeEvent
    public static void registerOverlays(RegisterGuiOverlaysEvent event) {
        event.registerAbove(VanillaGuiOverlay.HOTBAR.id(), "joinflow_progress",
                (gui, graphics, partialTick, width, height) -> {
                    if (!ClientJoinFlow.isActive() || !ClientConfig.uiEnabled()
                            || (!ClientConfig.showProgress() && !ClientConfig.showLoadState())
                            || net.minecraft.client.Minecraft.getInstance().screen instanceof JoinFlowScreen) return;
                    JoinFlowScreen.drawPanel(graphics, width, height, true);
                });
    }
}
