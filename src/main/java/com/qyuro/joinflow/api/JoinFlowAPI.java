package com.qyuro.joinflow.api;

import com.qyuro.joinflow.QyuroJoinFlow;
import com.qyuro.joinflow.network.SyncDataRegistry;
import net.minecraftforge.fml.loading.FMLEnvironment;
import com.qyuro.joinflow.server.JoinFlowRuntime;
import com.qyuro.joinflow.task.JoinTask;
import com.qyuro.joinflow.task.JoinTaskProvider;
import com.qyuro.joinflow.task.TaskPriority;
import net.minecraft.server.MinecraftServer;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Public integration surface for explicitly safe, JoinFlow-owned post-login work. */
public final class JoinFlowAPI {
    private static final int MAX_PROVIDERS = 128;
    private static final ConcurrentMap<String, JoinTaskProvider> PROVIDERS = new ConcurrentHashMap<>();
    private static final ConcurrentMap<String, ClientSyncHandler> CLIENT_HANDLERS = new ConcurrentHashMap<>();

    private JoinFlowAPI() {
    }

    /**
     * Registers one small task to be offered to every future player session. Providers are executed
     * on the logical server thread after login. Only POST_JOIN is schedulable: Forge's earlier
     * authentication/registry/vanilla-sync operations are intentionally not delayed by this API.
     */
    public static boolean registerTaskProvider(String ownerId, String taskId, JoinPhase phase,
                                               TaskPriority priority, long estimatedCostMicros,
                                               Set<String> dependencies, boolean safeToDefer,
                                               JoinTask task) {
        String owner = cleanId(ownerId, "ownerId");
        String name = cleanId(taskId, "taskId");
        if (phase != JoinPhase.POST_JOIN) {
            throw new IllegalArgumentException("Only POST_JOIN work is schedulable in this Forge-safe API");
        }
        if (!safeToDefer) {
            throw new IllegalArgumentException("JoinFlow only queues tasks explicitly marked safeToDefer");
        }
        if (priority == null || task == null) throw new IllegalArgumentException("priority and task are required");
        if (estimatedCostMicros < 1L || estimatedCostMicros > 60_000_000L) {
            throw new IllegalArgumentException("estimatedCostMicros must be between 1 and 60000000");
        }
        Set<String> deps = dependencies == null ? Set.of() : Set.copyOf(dependencies);
        if (deps.size() > 32) throw new IllegalArgumentException("At most 32 dependencies are supported");
        for (String dependency : deps) cleanQualifiedId(dependency);

        String key = owner + ":" + name;
        if (!PROVIDERS.containsKey(key) && PROVIDERS.size() >= MAX_PROVIDERS) {
            QyuroJoinFlow.LOGGER.warn("[QyuroJoinFlow] Task-provider limit reached; ignoring {}", key);
            return false;
        }
        JoinTaskProvider provider = new JoinTaskProvider(owner, name, phase, priority,
                estimatedCostMicros, deps, true, task);
        JoinTaskProvider previous = PROVIDERS.putIfAbsent(key, provider);
        if (previous != null) {
            QyuroJoinFlow.LOGGER.warn("[QyuroJoinFlow] Duplicate task provider {}; keeping the first registration", key);
            return false;
        }
        QyuroJoinFlow.LOGGER.info("[QyuroJoinFlow] Registered safe post-join provider {} ({}, estimate={} us)",
                key, priority, estimatedCostMicros);
        return true;
    }

    public static boolean unregisterTaskProvider(String ownerId, String taskId) {
        String key = cleanId(ownerId, "ownerId") + ":" + cleanId(taskId, "taskId");
        return PROVIDERS.remove(key) != null;
    }

    public static boolean disableProviderByQualifiedId(String qualifiedId) {
        if (qualifiedId == null || qualifiedId.length() > 130
                || !qualifiedId.matches("[a-z0-9_.-]{1,64}:[a-z0-9_.-]{1,64}")) return false;
        return PROVIDERS.remove(qualifiedId) != null;
    }

    public static List<JoinTaskProvider> taskProviders() {
        return List.copyOf(PROVIDERS.values());
    }

    /** Queues a one-off safe post-join task for an active session. The action runs on server thread. */
    public static void registerTask(MinecraftServer server, UUID playerId, String taskId,
                                    TaskPriority priority, long estimatedCostMicros, JoinTask task) {
        if (server == null || playerId == null || task == null || priority == null) {
            throw new IllegalArgumentException("server, playerId, priority and task are required");
        }
        String cleanTaskId = cleanId(taskId, "taskId");
        if (estimatedCostMicros < 1L || estimatedCostMicros > 60_000_000L) {
            throw new IllegalArgumentException("estimatedCostMicros must be between 1 and 60000000");
        }
        server.execute(() -> JoinFlowRuntime.submitPostJoinTask(server, playerId, cleanTaskId, priority,
                estimatedCostMicros, Set.of(), task));
    }

    /** Registers bounded, immutable static bytes for optional client sync. Mark cacheable only for
     * non-secret data which is safe to persist on the client. */
    public static boolean registerStaticData(String ownerId, String key, int version,
                                             byte[] payload, boolean cacheable) {
        String owner = cleanId(ownerId, "ownerId");
        String cleanKey = cleanSyncKey(key);
        if (payload == null) throw new IllegalArgumentException("payload is required");
        if (version < 0) throw new IllegalArgumentException("version must be non-negative");
        return SyncDataRegistry.register(owner, cleanKey, version, Arrays.copyOf(payload, payload.length), cacheable);
    }

    public static boolean unregisterStaticData(String ownerId, String key) {
        return SyncDataRegistry.unregister(cleanId(ownerId, "ownerId"), cleanSyncKey(key));
    }

    /** Registers an optional client-side decoder/application hook. It is invoked on the client thread
     * only, and must remain bounded or split its own work. No server-only code should register it. */
    public static boolean registerClientSyncHandler(String key, ClientSyncHandler handler) {
        if (FMLEnvironment.dist != net.minecraftforge.api.distmarker.Dist.CLIENT || handler == null) return false;
        String cleanKey = cleanSyncKey(key);
        if (!CLIENT_HANDLERS.containsKey(cleanKey) && CLIENT_HANDLERS.size() >= 128) return false;
        return CLIENT_HANDLERS.putIfAbsent(cleanKey, handler) == null;
    }

    public static boolean unregisterClientSyncHandler(String key) {
        return CLIENT_HANDLERS.remove(cleanSyncKey(key)) != null;
    }

    public static ClientSyncHandler clientSyncHandler(String key) {
        return CLIENT_HANDLERS.get(key);
    }

    /** Requests optional static data transfer. Unknown keys are ignored; no client value can create
     * a server registry entry or change server scheduling priority. */
    public static void requestStaticSync(MinecraftServer server, UUID playerId, String key) {
        if (server == null || playerId == null) throw new IllegalArgumentException("server and playerId are required");
        String cleanKey = cleanSyncKey(key);
        server.execute(() -> JoinFlowRuntime.requestStaticSync(playerId, cleanKey));
    }

    private static String cleanId(String value, String field) {
        if (value == null) throw new IllegalArgumentException(field + " is required");
        String clean = value.toLowerCase(Locale.ROOT);
        if (!clean.matches("[a-z0-9_.-]{1,64}")) throw new IllegalArgumentException("Invalid " + field + ": " + value);
        return clean;
    }

    private static String cleanQualifiedId(String value) {
        if (value == null || value.length() > 130 || !value.matches("[a-z0-9_.-]{1,64}:[a-z0-9_.-]{1,64}")) {
            throw new IllegalArgumentException("Dependency must be owner:task");
        }
        return value;
    }

    private static String cleanSyncKey(String value) {
        if (value == null || !value.matches("[a-z0-9_.-]{1,64}:[a-z0-9_./-]{1,96}")) {
            throw new IllegalArgumentException("Sync key must be a namespaced key, for example addon:static_data");
        }
        return value.toLowerCase(Locale.ROOT);
    }
}
