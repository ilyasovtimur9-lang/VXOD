package com.qyuro.joinflow.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.qyuro.joinflow.QyuroJoinFlow;
import com.qyuro.joinflow.config.ClientConfig;
import com.qyuro.joinflow.network.SyncDataRegistry;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Client cache for explicitly cacheable static payloads only. All file operations are off-thread. */
public final class ClientCache {
    private static final int MAX_INDEX_BYTES = 512 * 1024;
    private static final LinkedHashMap<CacheKey, CacheEntry> ENTRIES = new LinkedHashMap<>(16, 0.75f, true);
    private static Path directory;
    private static Path indexFile;
    private static ThreadPoolExecutor io;
    private static CompletableFuture<Map<CacheKey, CacheEntry>> loadFuture;
    private static boolean initialized;
    private static boolean loadComplete;
    private static long totalBytes;

    private ClientCache() {
    }

    public static void initialize() {
        if (initialized || !ClientConfig.enabled()) return;
        initialized = true;
        directory = FMLPaths.CONFIGDIR.get().resolve("qyurojoinflow/cache");
        indexFile = directory.resolve("index.json");
        io = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(8), task -> {
            Thread thread = new Thread(task, "QyuroJoinFlow-ClientCache");
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
        int maxEntries = ClientConfig.maxCachedEntries();
        int maxBytes = ClientConfig.maxCachedBytes();
        loadFuture = CompletableFuture.supplyAsync(() -> readCache(maxEntries, maxBytes), io);
    }

    /** Called from client tick; get() is used only after isDone(), so it never blocks. */
    public static void tickLoadCompletion() {
        if (!initialized || loadComplete || loadFuture == null || !loadFuture.isDone()) return;
        try {
            Map<CacheKey, CacheEntry> loaded = loadFuture.get();
            ENTRIES.clear();
            totalBytes = 0L;
            loaded.forEach((key, entry) -> {
                ENTRIES.put(key, entry);
                totalBytes += entry.payload.length;
            });
        } catch (Exception exception) {
            QyuroJoinFlow.LOGGER.warn("[QyuroJoinFlow] Client cache index could not be loaded; using an empty cache", exception);
            ENTRIES.clear();
            totalBytes = 0L;
        }
        loadComplete = true;
    }

    public static boolean contains(String key, int version, String hash) {
        CacheEntry entry = ENTRIES.get(new CacheKey(key, version));
        return loadComplete && entry != null && entry.hash.equals(hash);
    }

    public static byte[] get(String key, int version, String hash) {
        CacheEntry entry = ENTRIES.get(new CacheKey(key, version));
        if (!loadComplete || entry == null || !entry.hash.equals(hash)) return null;
        return entry.payload.clone();
    }

    static void putVerified(String key, int version, String hash, byte[] payload) {
        if (!ClientConfig.enabled() || !loadComplete || payload == null || payload.length > ClientConfig.maxCachedBytes()) return;

        CacheKey cacheKey = new CacheKey(key, version);
        CacheEntry previous = ENTRIES.remove(cacheKey);
        if (previous != null) totalBytes -= previous.payload.length;
        CacheEntry entry = new CacheEntry(hash, payload.clone());
        ENTRIES.put(cacheKey, entry);
        totalBytes += entry.payload.length;

        int maxEntries = ClientConfig.maxCachedEntries();
        int maxBytes = ClientConfig.maxCachedBytes();
        List<CacheKey> evicted = new ArrayList<>();
        Iterator<Map.Entry<CacheKey, CacheEntry>> iterator = ENTRIES.entrySet().iterator();
        while ((ENTRIES.size() > maxEntries || totalBytes > maxBytes) && iterator.hasNext()) {
            Map.Entry<CacheKey, CacheEntry> eldest = iterator.next();
            totalBytes -= eldest.getValue().payload.length;
            evicted.add(eldest.getKey());
            iterator.remove();
        }
        persist(entry, cacheKey, List.copyOf(ENTRIES.entrySet()), evicted);
    }

    private static void persist(CacheEntry entry, CacheKey changedKey,
                                List<Map.Entry<CacheKey, CacheEntry>> metadata, List<CacheKey> evicted) {
        if (io == null || io.isShutdown()) return;
        JsonArray array = new JsonArray();
        for (Map.Entry<CacheKey, CacheEntry> item : metadata) {
            JsonObject json = new JsonObject();
            json.addProperty("key", item.getKey().key);
            json.addProperty("version", item.getKey().version);
            json.addProperty("hash", item.getValue().hash);
            json.addProperty("size", item.getValue().payload.length);
            array.add(json);
        }
        String indexJson = array.toString();
        byte[] payloadCopy = entry.payload.clone();
        String fileName = fileName(changedKey.key, changedKey.version);
        List<String> evictedFiles = evicted.stream().map(key -> fileName(key.key, key.version)).toList();
        try {
            io.execute(() -> {
                try {
                    Files.createDirectories(directory);
                    writeAtomically(directory.resolve(fileName), payloadCopy);
                    for (String oldFile : evictedFiles) {
                        if (!oldFile.equals(fileName)) Files.deleteIfExists(directory.resolve(oldFile));
                    }
                    writeTextAtomically(indexFile, indexJson);
                } catch (IOException exception) {
                    QyuroJoinFlow.LOGGER.warn("[QyuroJoinFlow] Optional client cache write failed", exception);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException exception) {
            QyuroJoinFlow.LOGGER.debug("[QyuroJoinFlow] Client cache writer is busy; this cache update remains in memory only");
        }
    }

    private static Map<CacheKey, CacheEntry> readCache(int maxEntries, int maxBytes) {
        Map<CacheKey, CacheEntry> result = new LinkedHashMap<>();
        try {
            if (!Files.exists(indexFile) || Files.size(indexFile) > MAX_INDEX_BYTES) return Map.of();
            JsonElement root = JsonParser.parseString(Files.readString(indexFile, StandardCharsets.UTF_8));
            if (!root.isJsonArray()) return Map.of();
            JsonArray array = root.getAsJsonArray();
            long loadedBytes = 0L;
            int first = Math.max(0, array.size() - maxEntries);
            for (int i = first; i < array.size(); i++) {
                JsonElement element = array.get(i);
                if (!element.isJsonObject()) continue;
                JsonObject json = element.getAsJsonObject();
                String key = json.has("key") ? json.get("key").getAsString() : "";
                int version = json.has("version") ? json.get("version").getAsInt() : -1;
                String hash = json.has("hash") ? json.get("hash").getAsString() : "";
                long expectedSize = json.has("size") ? json.get("size").getAsLong() : -1L;
                if (!key.matches("[a-z0-9_.-]{1,64}:[a-z0-9_./-]{1,96}") || version < 0
                        || !hash.matches("[0-9a-f]{64}") || expectedSize < 0 || expectedSize > maxBytes) continue;
                Path dataFile = directory.resolve(fileName(key, version));
                if (!Files.exists(dataFile) || Files.size(dataFile) != expectedSize) continue;
                byte[] payload = Files.readAllBytes(dataFile);
                if (!SyncDataRegistry.sha256(payload).equals(hash) || loadedBytes + payload.length > maxBytes) continue;
                CacheKey cacheKey = new CacheKey(key, version);
                result.put(cacheKey, new CacheEntry(hash, payload));
                loadedBytes += payload.length;
            }
        } catch (IOException | RuntimeException exception) {
            QyuroJoinFlow.LOGGER.warn("[QyuroJoinFlow] Client cache could not be read", exception);
            return Map.of();
        }
        return Map.copyOf(result);
    }

    private static String fileName(String key, int version) {
        byte[] identity = (key + ":" + version).getBytes(StandardCharsets.UTF_8);
        return SyncDataRegistry.sha256(identity) + ".bin";
    }

    private static void writeAtomically(Path target, byte[] data) throws IOException {
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        Files.write(temporary, data);
        moveAtomically(temporary, target);
    }

    private static void writeTextAtomically(Path target, String text) throws IOException {
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(temporary, text, StandardCharsets.UTF_8);
        moveAtomically(temporary, target);
    }

    private static void moveAtomically(Path temporary, Path target) throws IOException {
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static int entryCount() { return ENTRIES.size(); }
    public static long totalBytes() { return totalBytes; }
    public static boolean isReady() { return loadComplete; }

    public static void close() {
        if (io == null) return;
        io.shutdown();
        try {
            if (!io.awaitTermination(500L, TimeUnit.MILLISECONDS)) io.shutdownNow();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            io.shutdownNow();
        }
        io = null;
        initialized = false;
        loadComplete = false;
        loadFuture = null;
        ENTRIES.clear();
        totalBytes = 0L;
    }

    private record CacheKey(String key, int version) { }
    private record CacheEntry(String hash, byte[] payload) { }
}
