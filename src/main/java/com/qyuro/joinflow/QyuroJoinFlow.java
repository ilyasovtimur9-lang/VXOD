package com.qyuro.joinflow;

import com.mojang.logging.LogUtils;
import com.qyuro.joinflow.config.ClientConfig;
import com.qyuro.joinflow.config.ServerConfig;
import com.qyuro.joinflow.network.JoinFlowNetwork;
import com.qyuro.joinflow.server.JoinFlowServerEvents;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

@Mod(QyuroJoinFlow.MODID)
public final class QyuroJoinFlow {
    public static final String MODID = "qyuro_joinflow";
    public static final String MOD_NAME = "Qyuro JoinFlow";
    public static final String VERSION = "1.0.0";
    public static final Logger LOGGER = LogUtils.getLogger();

    public QyuroJoinFlow(FMLJavaModLoadingContext context) {
        context.registerConfig(ModConfig.Type.COMMON, ServerConfig.SPEC, "qyurojoinflow-server.toml");
        context.registerConfig(ModConfig.Type.CLIENT, ClientConfig.SPEC, "qyurojoinflow-client.toml");
        JoinFlowNetwork.registerMessages();
        MinecraftForge.EVENT_BUS.register(new JoinFlowServerEvents());
        LOGGER.info("[QyuroJoinFlow] Mod bootstrap complete; Forge 1.20.1 / protocol {}", JoinFlowNetwork.PROTOCOL_VERSION);
    }
}
