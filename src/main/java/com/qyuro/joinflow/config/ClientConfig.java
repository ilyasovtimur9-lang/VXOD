package com.qyuro.joinflow.config;

import net.minecraftforge.common.ForgeConfigSpec;

/** Client-only preferences; this class intentionally has no Minecraft client references. */
public final class ClientConfig {
    public static final ForgeConfigSpec SPEC;

    private static final ForgeConfigSpec.BooleanValue ENABLED;
    private static final ForgeConfigSpec.BooleanValue DEBUG;
    private static final ForgeConfigSpec.BooleanValue UI_ENABLED;
    private static final ForgeConfigSpec.BooleanValue SHOW_PROGRESS;
    private static final ForgeConfigSpec.BooleanValue SHOW_LOAD_STATE;
    private static final ForgeConfigSpec.BooleanValue OPEN_SCREEN;
    private static final ForgeConfigSpec.DoubleValue CLIENT_TICK_BUDGET_MS;
    private static final ForgeConfigSpec.IntValue MAX_CACHED_ENTRIES;
    private static final ForgeConfigSpec.IntValue MAX_CACHED_BYTES;
    private static final ForgeConfigSpec.BooleanValue TELEMETRY_ENABLED;
    private static final ForgeConfigSpec.IntValue HEARTBEAT_INTERVAL_MS;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        builder.push("general");
        ENABLED = builder.define("enabled", true);
        DEBUG = builder.define("debug", false);
        builder.pop();

        builder.push("ui");
        UI_ENABLED = builder.define("enabled", true);
        SHOW_PROGRESS = builder.define("show_progress", true);
        SHOW_LOAD_STATE = builder.define("show_load_state", true);
        OPEN_SCREEN = builder.comment("If enabled, may show a non-pausing JoinFlow screen while JoinFlow-owned post-join work is active.")
                .define("open_screen", false);
        builder.pop();

        builder.push("sync");
        CLIENT_TICK_BUDGET_MS = builder.defineInRange("client_tick_budget_ms", 2.0, 0.1, 20.0);
        MAX_CACHED_ENTRIES = builder.defineInRange("max_cached_entries", 1000, 1, 10000);
        MAX_CACHED_BYTES = builder.defineInRange("max_cached_bytes", 4194304, 65536, 67108864);
        builder.pop();

        builder.push("telemetry");
        TELEMETRY_ENABLED = builder.define("enabled", true);
        HEARTBEAT_INTERVAL_MS = builder.defineInRange("interval_ms", 1000, 500, 10000);
        builder.pop();
        SPEC = builder.build();
    }

    private ClientConfig() {
    }

    public static boolean enabled() { return ENABLED.get(); }
    public static boolean debug() { return DEBUG.get(); }
    public static boolean uiEnabled() { return UI_ENABLED.get(); }
    public static boolean showProgress() { return SHOW_PROGRESS.get(); }
    public static boolean showLoadState() { return SHOW_LOAD_STATE.get(); }
    public static boolean openScreen() { return OPEN_SCREEN.get(); }
    public static double clientTickBudgetMs() { return CLIENT_TICK_BUDGET_MS.get(); }
    public static int maxCachedEntries() { return MAX_CACHED_ENTRIES.get(); }
    public static int maxCachedBytes() { return MAX_CACHED_BYTES.get(); }
    public static boolean telemetryEnabled() { return TELEMETRY_ENABLED.get(); }
    public static int heartbeatIntervalMs() { return HEARTBEAT_INTERVAL_MS.get(); }
}
