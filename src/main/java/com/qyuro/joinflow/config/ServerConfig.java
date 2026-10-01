package com.qyuro.joinflow.config;

import com.qyuro.joinflow.QyuroJoinFlow;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.config.ModConfigEvent;

/** Live server-side configuration. Values are read only on the server thread. */
@Mod.EventBusSubscriber(modid = QyuroJoinFlow.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ServerConfig {
    public static final ForgeConfigSpec SPEC;

    private static final ForgeConfigSpec.BooleanValue ENABLED;
    private static final ForgeConfigSpec.BooleanValue DEBUG;
    private static final ForgeConfigSpec.BooleanValue ADAPTIVE_ENABLED;
    private static final ForgeConfigSpec.BooleanValue AUTO_TUNING;
    private static final ForgeConfigSpec.DoubleValue ECO_MSPT;
    private static final ForgeConfigSpec.DoubleValue NORMAL_MSPT;
    private static final ForgeConfigSpec.DoubleValue PROTECTED_MSPT;
    private static final ForgeConfigSpec.DoubleValue CRITICAL_MSPT;
    private static final ForgeConfigSpec.DoubleValue EMERGENCY_MSPT;

    private static final ForgeConfigSpec.DoubleValue JOIN_NORMAL_MS;
    private static final ForgeConfigSpec.DoubleValue JOIN_PROTECTED_MS;
    private static final ForgeConfigSpec.DoubleValue JOIN_CRITICAL_MS;
    private static final ForgeConfigSpec.DoubleValue JOIN_EMERGENCY_MS;
    private static final ForgeConfigSpec.DoubleValue JOIN_MIN_MS;
    private static final ForgeConfigSpec.DoubleValue POST_NORMAL_MS;
    private static final ForgeConfigSpec.DoubleValue POST_PROTECTED_MS;
    private static final ForgeConfigSpec.DoubleValue POST_CRITICAL_MS;
    private static final ForgeConfigSpec.DoubleValue BACKGROUND_NORMAL_MS;
    private static final ForgeConfigSpec.DoubleValue BACKGROUND_PROTECTED_MS;

    private static final ForgeConfigSpec.IntValue MAX_CONCURRENT_JOINS;
    private static final ForgeConfigSpec.IntValue MAX_QUEUE_SIZE;
    private static final ForgeConfigSpec.IntValue MAX_TASKS_PER_PLAYER;
    private static final ForgeConfigSpec.IntValue MAX_TOTAL_TASKS;
    private static final ForgeConfigSpec.IntValue MAX_PENDING_BYTES_PER_PLAYER;
    private static final ForgeConfigSpec.BooleanValue ALLOW_PARALLEL_SAFE_TASKS;
    private static final ForgeConfigSpec.IntValue AGING_INTERVAL_TICKS;
    private static final ForgeConfigSpec.IntValue MAX_SESSION_SECONDS;
    private static final ForgeConfigSpec.IntValue MAX_TASKS_PER_TICK;

    private static final ForgeConfigSpec.BooleanValue SPIKE_GUARD_ENABLED;
    private static final ForgeConfigSpec.DoubleValue SPIKE_THRESHOLD_MS;
    private static final ForgeConfigSpec.IntValue SPIKES_BEFORE_CRITICAL;
    private static final ForgeConfigSpec.IntValue RECOVERY_TICKS;

    private static final ForgeConfigSpec.BooleanValue NETWORK_ENABLED;
    private static final ForgeConfigSpec.BooleanValue BATCHING_ENABLED;
    private static final ForgeConfigSpec.BooleanValue DELTA_SYNC_ENABLED;
    private static final ForgeConfigSpec.IntValue MAX_PACKET_BYTES;
    private static final ForgeConfigSpec.IntValue MAX_TELEMETRY_PACKETS_PER_SECOND;
    private static final ForgeConfigSpec.IntValue MAX_OUTBOUND_PACKETS_PER_TICK;
    private static final ForgeConfigSpec.IntValue MAX_OUTBOUND_QUEUE_ENTRIES;
    private static final ForgeConfigSpec.IntValue MAX_STATIC_SYNC_BYTES;

    private static final ForgeConfigSpec.BooleanValue CLIENT_ENABLED;
    private static final ForgeConfigSpec.BooleanValue SEND_PROGRESS;
    private static final ForgeConfigSpec.BooleanValue SEND_SERVER_STATE;
    private static final ForgeConfigSpec.IntValue HEARTBEAT_INTERVAL_MS;

    private static final ForgeConfigSpec.ConfigValue<String> PROFILING_MODE;
    private static final ForgeConfigSpec.IntValue HISTORY_SIZE;
    private static final ForgeConfigSpec.DoubleValue SLOW_TASK_MS;
    private static final ForgeConfigSpec.DoubleValue VERY_SLOW_TASK_MS;
    private static final ForgeConfigSpec.DoubleValue CRITICAL_TASK_MS;
    private static final ForgeConfigSpec.BooleanValue WRITE_REPORTS;
    private static final ForgeConfigSpec.IntValue WRITE_FULL_REPORT_EVERY;

    private static final ForgeConfigSpec.BooleanValue ORIGINS_ENABLED;
    private static final ForgeConfigSpec.BooleanValue APOLI_ENABLED;
    private static final ForgeConfigSpec.BooleanValue QYUROWL_ENABLED;
    private static final ForgeConfigSpec.BooleanValue QYUROLEVL_ENABLED;
    private static final ForgeConfigSpec.BooleanValue FAILSAFE_ENABLED;
    private static final ForgeConfigSpec.BooleanValue DISABLE_FAILED_ADAPTER;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();

        builder.push("general");
        ENABLED = builder.comment("Enable managed JoinFlow work and status monitoring.")
                .define("enabled", true);
        DEBUG = builder.comment("Enable verbose JoinFlow diagnostics. Disabled by default.")
                .define("debug", false);
        builder.pop();

        builder.push("adaptive");
        ADAPTIVE_ENABLED = builder.comment("Enable load-aware budget reduction and recovery.")
                .define("enabled", true);
        AUTO_TUNING = builder.comment("Apply adaptive budget levels; when false, fixed normal budgets are used.")
                .define("auto_tuning", true);
        ECO_MSPT = builder.defineInRange("eco_mspt", 12.0, 1.0, 2000.0);
        NORMAL_MSPT = builder.defineInRange("normal_mspt", 20.0, 1.0, 2000.0);
        PROTECTED_MSPT = builder.defineInRange("protected_mspt", 35.0, 1.0, 2000.0);
        CRITICAL_MSPT = builder.defineInRange("critical_mspt", 60.0, 1.0, 2000.0);
        EMERGENCY_MSPT = builder.defineInRange("emergency_mspt", 100.0, 1.0, 2000.0);
        builder.pop();

        builder.push("budget");
        JOIN_MIN_MS = builder.defineInRange("join_min_ms", 0.25, 0.0, 50.0);
        JOIN_NORMAL_MS = builder.defineInRange("join_normal_ms", 4.0, 0.0, 50.0);
        JOIN_PROTECTED_MS = builder.defineInRange("join_protected_ms", 2.0, 0.0, 50.0);
        JOIN_CRITICAL_MS = builder.defineInRange("join_critical_ms", 0.75, 0.0, 50.0);
        JOIN_EMERGENCY_MS = builder.defineInRange("join_emergency_ms", 0.25, 0.0, 50.0);
        POST_NORMAL_MS = builder.defineInRange("post_join_normal_ms", 2.0, 0.0, 50.0);
        POST_PROTECTED_MS = builder.defineInRange("post_join_protected_ms", 1.0, 0.0, 50.0);
        POST_CRITICAL_MS = builder.defineInRange("post_join_critical_ms", 0.5, 0.0, 50.0);
        BACKGROUND_NORMAL_MS = builder.defineInRange("background_normal_ms", 0.5, 0.0, 20.0);
        BACKGROUND_PROTECTED_MS = builder.defineInRange("background_protected_ms", 0.25, 0.0, 20.0);
        builder.pop();

        builder.push("queue");
        MAX_CONCURRENT_JOINS = builder.defineInRange("max_concurrent_joins", 1, 1, 64);
        MAX_QUEUE_SIZE = builder.defineInRange("max_queue_size", 100, 1, 10000);
        MAX_TASKS_PER_PLAYER = builder.defineInRange("max_tasks_per_player", 500, 1, 10000);
        MAX_TOTAL_TASKS = builder.defineInRange("max_total_tasks", 5000, 1, 50000);
        MAX_PENDING_BYTES_PER_PLAYER = builder.defineInRange("max_pending_bytes_per_player", 262144, 1024, 16777216);
        ALLOW_PARALLEL_SAFE_TASKS = builder.define("allow_parallel_safe_tasks", true);
        AGING_INTERVAL_TICKS = builder.defineInRange("aging_interval_ticks", 200, 1, 12000);
        MAX_SESSION_SECONDS = builder.defineInRange("max_join_session_seconds", 120, 5, 3600);
        MAX_TASKS_PER_TICK = builder.defineInRange("max_tasks_per_tick", 32, 1, 1024);
        builder.pop();

        builder.push("spike_guard");
        SPIKE_GUARD_ENABLED = builder.define("enabled", true);
        SPIKE_THRESHOLD_MS = builder.defineInRange("spike_threshold_ms", 75.0, 1.0, 2000.0);
        SPIKES_BEFORE_CRITICAL = builder.defineInRange("spikes_before_critical", 2, 1, 20);
        RECOVERY_TICKS = builder.defineInRange("recovery_ticks", 20, 1, 12000);
        builder.pop();

        builder.push("network");
        NETWORK_ENABLED = builder.define("enabled", true);
        BATCHING_ENABLED = builder.define("batching", true);
        DELTA_SYNC_ENABLED = builder.define("delta_sync", true);
        MAX_PACKET_BYTES = builder.defineInRange("max_packet_bytes", 24000, 512, 1048576);
        MAX_TELEMETRY_PACKETS_PER_SECOND = builder.defineInRange("max_telemetry_packets_per_second", 4, 1, 100);
        MAX_OUTBOUND_PACKETS_PER_TICK = builder.defineInRange("max_outbound_packets_per_tick", 8, 1, 256);
        MAX_OUTBOUND_QUEUE_ENTRIES = builder.defineInRange("max_outbound_queue_entries", 1024, 16, 32768);
        MAX_STATIC_SYNC_BYTES = builder.defineInRange("max_static_sync_bytes", 1048576, 1024, 16777216);
        builder.pop();

        builder.push("client");
        CLIENT_ENABLED = builder.define("enabled", true);
        SEND_PROGRESS = builder.define("send_progress", true);
        SEND_SERVER_STATE = builder.define("send_server_state", true);
        HEARTBEAT_INTERVAL_MS = builder.defineInRange("heartbeat_interval_ms", 1000, 500, 10000);
        builder.pop();

        builder.push("profiling");
        PROFILING_MODE = builder.comment("OFF, BASIC or FULL. FULL adds per-task/queue detail.")
                .define("mode", "BASIC", value -> value instanceof String text
                        && (text.equalsIgnoreCase("OFF") || text.equalsIgnoreCase("BASIC") || text.equalsIgnoreCase("FULL")));
        HISTORY_SIZE = builder.defineInRange("history_size", 100, 10, 1000);
        SLOW_TASK_MS = builder.defineInRange("slow_task_ms", 5.0, 0.1, 10000.0);
        VERY_SLOW_TASK_MS = builder.defineInRange("very_slow_task_ms", 20.0, 0.1, 10000.0);
        CRITICAL_TASK_MS = builder.defineInRange("critical_task_ms", 50.0, 0.1, 10000.0);
        WRITE_REPORTS = builder.define("write_reports", false);
        WRITE_FULL_REPORT_EVERY = builder.defineInRange("write_full_report_every", 10, 1, 100000);
        builder.pop();

        builder.push("compatibility");
        ORIGINS_ENABLED = builder.define("origins", true);
        APOLI_ENABLED = builder.define("apoli", true);
        QYUROWL_ENABLED = builder.define("qyurowl", true);
        QYUROLEVL_ENABLED = builder.define("qyurolevl", true);
        builder.pop();

        builder.push("failsafe");
        FAILSAFE_ENABLED = builder.define("enabled", true);
        DISABLE_FAILED_ADAPTER = builder.define("disable_failed_optional_adapter", true);
        builder.pop();

        SPEC = builder.build();
    }

    private ServerConfig() {
    }

    @SubscribeEvent
    public static void onConfigLoad(ModConfigEvent.Loading event) {
        if (event.getConfig().getSpec() == SPEC) {
            QyuroJoinFlow.LOGGER.info("[QyuroJoinFlow] Server configuration loaded: {}", event.getConfig().getFileName());
        }
    }

    @SubscribeEvent
    public static void onConfigReload(ModConfigEvent.Reloading event) {
        if (event.getConfig().getSpec() == SPEC) {
            QyuroJoinFlow.LOGGER.info("[QyuroJoinFlow] Server configuration reloaded; adaptive controllers will apply new values on the next tick.");
        }
    }

    public static boolean enabled() { return ENABLED.get(); }
    public static boolean debug() { return DEBUG.get(); }
    public static boolean adaptiveEnabled() { return ADAPTIVE_ENABLED.get() && AUTO_TUNING.get(); }
    public static boolean autoTuningEnabled() { return AUTO_TUNING.get(); }
    public static double ecoMspt() { return ECO_MSPT.get(); }
    public static double normalMspt() { return NORMAL_MSPT.get(); }
    public static double protectedMspt() { return PROTECTED_MSPT.get(); }
    public static double criticalMspt() { return CRITICAL_MSPT.get(); }
    public static double emergencyMspt() { return EMERGENCY_MSPT.get(); }
    public static double joinMinMs() { return JOIN_MIN_MS.get(); }
    public static double joinNormalMs() { return JOIN_NORMAL_MS.get(); }
    public static double joinProtectedMs() { return JOIN_PROTECTED_MS.get(); }
    public static double joinCriticalMs() { return JOIN_CRITICAL_MS.get(); }
    public static double joinEmergencyMs() { return JOIN_EMERGENCY_MS.get(); }
    public static double postNormalMs() { return POST_NORMAL_MS.get(); }
    public static double postProtectedMs() { return POST_PROTECTED_MS.get(); }
    public static double postCriticalMs() { return POST_CRITICAL_MS.get(); }
    public static double backgroundNormalMs() { return BACKGROUND_NORMAL_MS.get(); }
    public static double backgroundProtectedMs() { return BACKGROUND_PROTECTED_MS.get(); }
    public static int maxConcurrentJoins() { return MAX_CONCURRENT_JOINS.get(); }
    public static int maxQueueSize() { return MAX_QUEUE_SIZE.get(); }
    public static int maxTasksPerPlayer() { return MAX_TASKS_PER_PLAYER.get(); }
    public static int maxTotalTasks() { return MAX_TOTAL_TASKS.get(); }
    public static int maxPendingBytesPerPlayer() { return MAX_PENDING_BYTES_PER_PLAYER.get(); }
    public static boolean allowParallelSafeTasks() { return ALLOW_PARALLEL_SAFE_TASKS.get(); }
    public static int agingIntervalTicks() { return AGING_INTERVAL_TICKS.get(); }
    public static int maxSessionSeconds() { return MAX_SESSION_SECONDS.get(); }
    public static int maxTasksPerTick() { return MAX_TASKS_PER_TICK.get(); }
    public static boolean spikeGuardEnabled() { return SPIKE_GUARD_ENABLED.get(); }
    public static double spikeThresholdMs() { return SPIKE_THRESHOLD_MS.get(); }
    public static int spikesBeforeCritical() { return SPIKES_BEFORE_CRITICAL.get(); }
    public static int recoveryTicks() { return RECOVERY_TICKS.get(); }
    public static boolean networkEnabled() { return NETWORK_ENABLED.get(); }
    public static boolean batchingEnabled() { return BATCHING_ENABLED.get(); }
    public static boolean deltaSyncEnabled() { return DELTA_SYNC_ENABLED.get(); }
    public static int maxPacketBytes() { return MAX_PACKET_BYTES.get(); }
    public static int maxTelemetryPacketsPerSecond() { return MAX_TELEMETRY_PACKETS_PER_SECOND.get(); }
    public static int maxOutboundPacketsPerTick() { return MAX_OUTBOUND_PACKETS_PER_TICK.get(); }
    public static int maxOutboundQueueEntries() { return MAX_OUTBOUND_QUEUE_ENTRIES.get(); }
    public static int maxStaticSyncBytes() { return MAX_STATIC_SYNC_BYTES.get(); }
    public static boolean clientEnabled() { return CLIENT_ENABLED.get(); }
    public static boolean sendProgress() { return SEND_PROGRESS.get(); }
    public static boolean sendServerState() { return SEND_SERVER_STATE.get(); }
    public static int heartbeatIntervalMs() { return HEARTBEAT_INTERVAL_MS.get(); }
    public static String profilingMode() { return PROFILING_MODE.get().toUpperCase(java.util.Locale.ROOT); }
    public static int historySize() { return HISTORY_SIZE.get(); }
    public static double slowTaskMs() { return SLOW_TASK_MS.get(); }
    public static double verySlowTaskMs() { return VERY_SLOW_TASK_MS.get(); }
    public static double criticalTaskMs() { return CRITICAL_TASK_MS.get(); }
    public static boolean writeReports() { return WRITE_REPORTS.get(); }
    public static int writeFullReportEvery() { return WRITE_FULL_REPORT_EVERY.get(); }
    public static boolean originsEnabled() { return ORIGINS_ENABLED.get(); }
    public static boolean apoliEnabled() { return APOLI_ENABLED.get(); }
    public static boolean qyurowlEnabled() { return QYUROWL_ENABLED.get(); }
    public static boolean qyurolevlEnabled() { return QYUROLEVL_ENABLED.get(); }
    public static boolean failsafeEnabled() { return FAILSAFE_ENABLED.get(); }
    public static boolean disableFailedOptionalAdapter() { return DISABLE_FAILED_ADAPTER.get(); }
}
